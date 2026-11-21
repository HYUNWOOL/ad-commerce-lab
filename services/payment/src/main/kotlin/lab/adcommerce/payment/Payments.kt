package lab.adcommerce.payment

import lab.adcommerce.common.EventEnvelope
import lab.adcommerce.common.Inbox
import lab.adcommerce.common.NotFound
import lab.adcommerce.common.Outbox
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.LocalDate
import java.util.UUID

enum class PaymentStatus { REQUESTED, APPROVED, FAILED, UNKNOWN }

data class OrderPlaced(
    val orderId: UUID, val partnerId: String, val productId: Long, val amount: Long,
    val cpvPrice: Long, val startDate: LocalDate, val endDate: LocalDate,
)
data class PaymentApproved(val orderId: UUID, val paymentId: UUID, val partnerId: String, val amount: Long, val pgTransactionId: String)
data class PaymentFailed(val orderId: UUID, val paymentId: UUID, val reason: String)

data class PaymentView(
    val id: UUID,
    val orderId: UUID,
    val amount: Long,
    val status: String,
    val pgTransactionId: String?,
    val failureReason: String?,
)

@Service
class PaymentService(
    private val jdbc: JdbcClient,
    private val inbox: Inbox,
    private val outbox: Outbox,
    private val pg: PgClient,
    private val tx: TransactionTemplate,
    @Value("\${payment.reconcile.unknown-after:5s}") private val unknownAfter: Duration,
    @Value("\${payment.reconcile.requested-after:15s}") private val requestedAfter: Duration,
    @Value("\${payment.reconcile.give-up-after:10m}") private val giveUpAfter: Duration,
    @Value("\${payment.reconcile.batch:20}") private val batch: Int,
    /** PG가 받지 않았을 때(503·서킷 열림) defer = 보류 후 재시도(ADR-0006), fail = 바로 실패. fail은 S4 비교 실험용이다. */
    @Value("\${payment.pg-unavailable:defer}") private val onUnavailable: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** expired: 결제를 만든 지 give-up-after가 지났다. */
    private data class Due(val id: UUID, val orderId: UUID, val partnerId: String, val amount: Long, val status: String, val expired: Boolean)

    /**
     * 트랜잭션 1: Inbox + 결제 생성(주문당 하나) → 트랜잭션 밖: PG 승인 → 트랜잭션 2: 상태 전이 + 결과 이벤트.
     * PG 호출 동안 DB 커넥션을 붙잡지 않는다(P1).
     */
    fun handleOrderPlaced(eventId: UUID, order: OrderPlaced) {
        val paymentId = tx.execute {
            if (!inbox.firstTime(eventId, "payment")) return@execute null
            val id = UUID.randomUUID()
            val inserted = jdbc.sql(
                """
                INSERT INTO payments (id, order_id, partner_id, amount, status)
                VALUES (:id, :orderId, :partnerId, :amount, 'REQUESTED') ON CONFLICT (order_id) DO NOTHING
                """.trimIndent(),
            ).param("id", id).param("orderId", order.orderId).param("partnerId", order.partnerId).param("amount", order.amount).update()
            if (inserted == 1) id else null
        } ?: return

        when (val outcome = pg.approve(paymentId, order.orderId, order.amount)) {
            is PgOutcome.Approved, is PgOutcome.Declined -> settle(paymentId, order.orderId, order.partnerId, order.amount, outcome)
            // 503·연결 실패·서킷 열림: PG가 받지 않았다. 보류해 두면 재조회가 나중에 같은 멱등키로 다시 보낸다(ADR-0006).
            PgOutcome.Unavailable -> tx.executeWithoutResult {
                if (onUnavailable == "fail") fail(paymentId, order.orderId, "PG_UNAVAILABLE") else defer(paymentId)
            }
            // 결과를 모르면 실패로 확정하지 않는다. 재조회가 확정한다(FR-202, ADR-0004).
            PgOutcome.Unknown -> tx.executeWithoutResult { move(paymentId, PaymentStatus.UNKNOWN, null, "PG_TIMEOUT") }
        }
    }

    @Scheduled(fixedDelayString = "\${payment.reconcile.interval-ms:5000}")
    fun reconcile() {
        while (reconcileBatch() == batch) Unit
    }

    /**
     * UNKNOWN과 보류·방치된 REQUESTED를 PG에 물어 확정한다(ADR-0004, ADR-0006). 집기와 확정만 트랜잭션이고 PG 호출은 그 사이에 한다.
     * - PG에 있음 → 그 결과로 확정.
     * - PG에 없음 → 같은 멱등키로 승인을 다시 보낸다(PG가 키 단위로 멱등이라 승인은 한 번). 기한이 지났으면 보내지 않고 실패로 확정한다.
     * - PG가 조회에 대답하지 못함 → 건드리지 않는다. 처음 요청이 처리됐는지 이걸로는 알 수 없다.
     */
    fun reconcileBatch(): Int {
        val due = claimDue()
        due.forEach { p ->
            val found = pg.find(p.id)
            when {
                found is PgOutcome.Approved || found is PgOutcome.Declined -> settle(p, found, "조회")
                found != null -> log.info("결제 {} PG 조회 실패({}), 다음 회차에 다시 본다", p.id, found)
                // PG에 없고 기한도 지났다. PG가 한 번도 처리하지 않은 결제라 실패로 확정해도 돈이 어긋나지 않는다.
                p.expired -> tx.executeWithoutResult { fail(p.id, p.orderId, "PG_UNAVAILABLE") }.also { log.info("결제 {} 기한 안에 승인 못 해 실패로 확정", p.id) }
                else -> when (val again = pg.approve(p.id, p.orderId, p.amount)) {
                    is PgOutcome.Approved, is PgOutcome.Declined -> settle(p, again, "재승인")
                    // 다시 보낸 승인이 타임아웃되면 이제는 PG가 처리했을 수 있다. 실패로 포기하지 않도록 UNKNOWN으로 옮긴다.
                    PgOutcome.Unknown -> if (p.status == "REQUESTED") tx.executeWithoutResult { move(p.id, PaymentStatus.UNKNOWN, null, "PG_TIMEOUT") }
                    PgOutcome.Unavailable -> log.info("결제 {} PG가 아직 받지 않음, 다음 회차에 다시 보낸다", p.id)
                }
            }
        }
        return due.size
    }

    private fun settle(p: Due, outcome: PgOutcome, how: String) {
        settle(p.id, p.orderId, p.partnerId, p.amount, outcome)
        log.info("결제 {} {}로 확정: {} → {}", p.id, how, p.status, outcome)
    }

    fun findByOrder(orderId: UUID): PaymentView? =
        jdbc.sql("SELECT * FROM payments WHERE order_id = :orderId").param("orderId", orderId)
            .query(PaymentView::class.java).optional().orElse(null)

    /** updated_at을 지금으로 바꿔 집는다. 다른 인스턴스나 다음 회차가 같은 행을 바로 다시 집지 않는다(임대). */
    private fun claimDue(): List<Due> = tx.execute {
        jdbc.sql(
            """
            UPDATE payments SET updated_at = now(), reconcile_attempts = reconcile_attempts + 1
            WHERE id IN (
                SELECT id FROM payments
                WHERE (status = 'UNKNOWN' AND updated_at < now() - make_interval(secs => :unknownAfter))
                   OR (status = 'REQUESTED' AND updated_at < now() - make_interval(secs => :requestedAfter))
                ORDER BY updated_at LIMIT :limit FOR UPDATE SKIP LOCKED
            )
            RETURNING id, order_id, partner_id, amount, status, created_at < now() - make_interval(secs => :giveUpAfter) AS expired
            """.trimIndent(),
        ).param("unknownAfter", unknownAfter.toMillis() / 1000.0)
            .param("requestedAfter", requestedAfter.toMillis() / 1000.0)
            .param("giveUpAfter", giveUpAfter.toMillis() / 1000.0)
            .param("limit", batch)
            .query { rs, _ ->
                Due(rs.getObject("id", UUID::class.java), rs.getObject("order_id", UUID::class.java), rs.getString("partner_id"), rs.getLong("amount"), rs.getString("status"), rs.getBoolean("expired"))
            }
            .list()
    }!!

    /** 결제는 REQUESTED로 두고 사유만 남긴다. 재조회가 requested-after 뒤에 다시 집는다. */
    private fun defer(paymentId: UUID) {
        jdbc.sql("UPDATE payments SET failure_reason = 'PG_UNAVAILABLE', updated_at = now() WHERE id = :id AND status = 'REQUESTED'")
            .param("id", paymentId).update()
    }

    /** PG가 답한 결과(승인·거절)로 확정하고 결과 이벤트를 낸다. 처음 흐름과 재조회가 같이 쓴다. */
    private fun settle(paymentId: UUID, orderId: UUID, partnerId: String, amount: Long, outcome: PgOutcome) = tx.executeWithoutResult {
        when (outcome) {
            is PgOutcome.Approved -> if (move(paymentId, PaymentStatus.APPROVED, outcome.transactionId, null)) {
                outbox.append(
                    Topics.PAYMENT_RESULT, "PaymentApproved", orderId.toString(),
                    PaymentApproved(orderId, paymentId, partnerId, amount, outcome.transactionId),
                )
            }
            is PgOutcome.Declined -> fail(paymentId, orderId, "DECLINED")
            else -> error("확정할 수 없는 결과: $outcome")
        }
    }

    private fun fail(paymentId: UUID, orderId: UUID, reason: String) {
        if (move(paymentId, PaymentStatus.FAILED, null, reason)) {
            outbox.append(Topics.PAYMENT_RESULT, "PaymentFailed", orderId.toString(), PaymentFailed(orderId, paymentId, reason))
        }
    }

    /** 조건부 전이. REQUESTED → 모든 상태, UNKNOWN → APPROVED·FAILED. 이미 확정된 결제는 바꾸지 않는다. */
    private fun move(paymentId: UUID, to: PaymentStatus, transactionId: String?, reason: String?): Boolean {
        val from = if (to == PaymentStatus.UNKNOWN) listOf("REQUESTED") else listOf("REQUESTED", "UNKNOWN")
        val updated = jdbc.sql(
            """
            UPDATE payments SET status = :to, pg_transaction_id = :tx, failure_reason = :reason, updated_at = now()
            WHERE id = :id AND status IN (:from)
            """.trimIndent(),
        ).param("to", to.name).param("tx", transactionId).param("reason", reason).param("id", paymentId).param("from", from).update()
        if (updated == 0) log.warn("결제 {} → {} 전이 건너뜀(이미 처리됨)", paymentId, to)
        return updated == 1
    }
}

@Component
class OrderPlacedListener(private val payments: PaymentService, private val mapper: JsonMapper) {
    @KafkaListener(topics = [Topics.ORDER], groupId = "payment")
    fun on(message: String) {
        val event = mapper.readValue(message, EventEnvelope::class.java)
        if (event.type != "OrderPlaced") return
        payments.handleOrderPlaced(event.eventId, mapper.treeToValue(event.payload, OrderPlaced::class.java))
    }
}

@RestController
class PaymentController(private val payments: PaymentService) {
    @GetMapping("/payments")
    fun byOrder(@RequestParam orderId: UUID): PaymentView = payments.findByOrder(orderId) ?: throw NotFound("주문 $orderId 의 결제가 없습니다")
}

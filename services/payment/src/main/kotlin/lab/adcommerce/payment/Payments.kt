package lab.adcommerce.payment

import lab.adcommerce.common.EventEnvelope
import lab.adcommerce.common.Inbox
import lab.adcommerce.common.NotFound
import lab.adcommerce.common.Outbox
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate
import java.util.UUID

enum class PaymentStatus { REQUESTED, APPROVED, FAILED, UNKNOWN }

data class OrderPlaced(
    val orderId: UUID, val partnerId: String, val productId: Long, val amount: Long,
    val cpvPrice: Long, val startDate: LocalDate, val endDate: LocalDate,
)
data class PaymentApproved(val orderId: UUID, val paymentId: UUID, val amount: Long, val pgTransactionId: String)
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
) {
    private val log = LoggerFactory.getLogger(javaClass)

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

        val outcome = pg.approve(paymentId, order.orderId, order.amount)

        tx.executeWithoutResult {
            when (outcome) {
                is PgOutcome.Approved -> if (move(paymentId, PaymentStatus.APPROVED, outcome.transactionId, null)) {
                    outbox.append(
                        Topics.PAYMENT_RESULT, "PaymentApproved", order.orderId.toString(),
                        PaymentApproved(order.orderId, paymentId, order.amount, outcome.transactionId),
                    )
                }
                is PgOutcome.Declined -> fail(paymentId, order.orderId, "DECLINED")
                PgOutcome.Unavailable -> fail(paymentId, order.orderId, "PG_UNAVAILABLE")
                // 결과를 모르면 실패로 확정하지 않는다. M2에서 재조회 스케줄러가 확정한다(FR-202).
                PgOutcome.Unknown -> move(paymentId, PaymentStatus.UNKNOWN, null, "PG_TIMEOUT")
            }
        }
    }

    fun findByOrder(orderId: UUID): PaymentView? =
        jdbc.sql("SELECT * FROM payments WHERE order_id = :orderId").param("orderId", orderId)
            .query(PaymentView::class.java).optional().orElse(null)

    private fun fail(paymentId: UUID, orderId: UUID, reason: String) {
        if (move(paymentId, PaymentStatus.FAILED, null, reason)) {
            outbox.append(Topics.PAYMENT_RESULT, "PaymentFailed", orderId.toString(), PaymentFailed(orderId, paymentId, reason))
        }
    }

    private fun move(paymentId: UUID, to: PaymentStatus, transactionId: String?, reason: String?): Boolean {
        val updated = jdbc.sql(
            """
            UPDATE payments SET status = :to, pg_transaction_id = :tx, failure_reason = :reason, updated_at = now()
            WHERE id = :id AND status = 'REQUESTED'
            """.trimIndent(),
        ).param("to", to.name).param("tx", transactionId).param("reason", reason).param("id", paymentId).update()
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

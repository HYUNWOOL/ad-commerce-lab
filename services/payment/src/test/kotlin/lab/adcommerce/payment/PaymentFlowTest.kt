package lab.adcommerce.payment

import lab.adcommerce.common.EventEnvelope
import lab.adcommerce.common.test.InfraContainers
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.kafka.core.KafkaTemplate
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

@SpringBootTest(
    properties = [
        "payment.reconcile.unknown-after=0s", // 테스트에서는 기다리지 않고 바로 집는다
        "payment.reconcile.requested-after=0s",
        "payment.reconcile.interval-ms=3600000", // 스케줄러 대신 테스트가 reconcileBatch()를 직접 부른다
    ],
)
@Import(InfraContainers::class, PaymentFlowTest.StubPgConfig::class)
class PaymentFlowTest {

    /**
     * 주문 금액 끝자리로 PG의 행동을 정한다.
     * 1 → 거절, 2 → 타임아웃이지만 PG는 승인함, 3 → 타임아웃이고 PG에 닿지 않음(다시 보내면 승인),
     * 4 → 타임아웃이고 조회도 계속 실패, 5 → 처음엔 PG가 받지 않음(503·서킷 열림), 다시 보내면 승인, 그 외 → 승인.
     */
    class StubPg : PgClient {
        val calls = ConcurrentHashMap<UUID, AtomicInteger>()
        private val approveCalls = ConcurrentHashMap<UUID, AtomicInteger>()
        private val amounts = ConcurrentHashMap<UUID, Long>()
        /** PG 쪽에 기록된 결제(멱등키 = 결제 ID). */
        val recorded = ConcurrentHashMap<UUID, PgOutcome>()

        override fun approve(paymentId: UUID, orderId: UUID, amount: Long): PgOutcome {
            calls.computeIfAbsent(orderId) { AtomicInteger() }.incrementAndGet()
            amounts[paymentId] = amount
            val nth = approveCalls.computeIfAbsent(paymentId) { AtomicInteger() }.incrementAndGet()
            recorded[paymentId]?.let { return it } // 같은 멱등키면 처음 결과
            return when (amount % 10) {
                1L -> PgOutcome.Declined("LIMIT_EXCEEDED").also { recorded[paymentId] = it }
                2L -> PgOutcome.Unknown.also { recorded[paymentId] = PgOutcome.Approved("pg-$paymentId") }
                3L -> if (nth == 1) PgOutcome.Unknown else PgOutcome.Approved("pg-$paymentId").also { recorded[paymentId] = it }
                4L -> PgOutcome.Unknown
                5L -> if (nth == 1) PgOutcome.Unavailable else PgOutcome.Approved("pg-$paymentId").also { recorded[paymentId] = it }
                else -> PgOutcome.Approved("pg-$paymentId").also { recorded[paymentId] = it }
            }
        }

        override fun find(paymentId: UUID): PgOutcome? =
            if (amounts[paymentId]?.rem(10) == 4L) PgOutcome.Unavailable else recorded[paymentId]

        /** 멱등키별로 PG가 실제로 취소한 금액(같은 키는 한 번만 취소). */
        val cancelled = ConcurrentHashMap<UUID, Long>()
        val cancelCalls = AtomicInteger()

        override fun cancel(transactionId: String, amount: Long, idempotencyKey: UUID) {
            cancelCalls.incrementAndGet()
            cancelled.putIfAbsent(idempotencyKey, amount)
        }
    }

    @TestConfiguration
    class StubPgConfig {
        @Bean @Primary fun stubPg() = StubPg()
    }

    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var kafka: KafkaTemplate<String, String>
    @Autowired lateinit var mapper: JsonMapper
    @Autowired lateinit var pg: StubPg
    @Autowired lateinit var service: PaymentService

    private fun publishOrder(orderId: UUID, amount: Long, eventId: UUID = UUID.randomUUID()) {
        val payload = OrderPlaced(orderId, "P-1", 1, amount, 50, LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 7))
        val envelope = EventEnvelope(eventId, "OrderPlaced", 1, Instant.now(), orderId.toString(), mapper.valueToTree(payload))
        kafka.send("campaign.order", orderId.toString(), mapper.writeValueAsString(envelope)).get()
    }

    private fun status(orderId: UUID): String? =
        jdbc.sql("SELECT status FROM payments WHERE order_id = :id").param("id", orderId).query(String::class.java).optional().orElse(null)

    private fun resultEvents(orderId: UUID): List<String> =
        jdbc.sql("SELECT payload FROM outbox_events WHERE message_key = :id").param("id", orderId.toString())
            .query(String::class.java).list().filterNotNull().map { mapper.readTree(it).get("type").asString() }

    private fun eventually(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos()
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(200)
        }
        throw AssertionError("20초 안에 만족하지 않음: $what")
    }

    @Test
    fun `주문 이벤트를 받으면 PG 승인 후 PaymentApproved를 발행한다`() {
        val orderId = UUID.randomUUID()
        publishOrder(orderId, 300_000)

        eventually("APPROVED") { status(orderId) == "APPROVED" }
        assertThat(resultEvents(orderId)).containsExactly("PaymentApproved")
    }

    @Test
    fun `같은 이벤트가 두 번 오거나 같은 주문이 다시 와도 결제는 하나이고 PG는 한 번만 호출한다`() {
        val orderId = UUID.randomUUID()
        val eventId = UUID.randomUUID()
        publishOrder(orderId, 300_000, eventId)
        publishOrder(orderId, 300_000, eventId) // 같은 이벤트 재전달(Inbox가 막음)
        publishOrder(orderId, 300_000) // 같은 주문의 재발행(order_id 유니크가 막음)

        eventually("APPROVED") { status(orderId) == "APPROVED" }
        Thread.sleep(1500)
        assertThat(jdbc.sql("SELECT count(*) FROM payments WHERE order_id = :id").param("id", orderId).query(Int::class.java).single()).isEqualTo(1)
        assertThat(pg.calls[orderId]!!.get()).isEqualTo(1)
        assertThat(resultEvents(orderId)).containsExactly("PaymentApproved")
    }

    @Test
    fun `PG가 거절하면 FAILED와 PaymentFailed를 낸다`() {
        val orderId = UUID.randomUUID()
        publishOrder(orderId, 300_001)

        eventually("FAILED") { status(orderId) == "FAILED" }
        assertThat(resultEvents(orderId)).containsExactly("PaymentFailed")
    }

    @Test
    fun `PG 응답을 못 받으면 실패로 확정하지 않고 UNKNOWN으로 둔다`() {
        val orderId = UUID.randomUUID()
        publishOrder(orderId, 300_002)

        eventually("UNKNOWN") { status(orderId) == "UNKNOWN" }
        assertThat(resultEvents(orderId)).isEmpty()
    }

    @Test
    fun `S5 타임아웃됐지만 PG가 승인했으면 재조회가 APPROVED로 확정하고 PaymentApproved를 한 번 낸다`() {
        val orderId = UUID.randomUUID()
        publishOrder(orderId, 300_012)
        eventually("UNKNOWN") { status(orderId) == "UNKNOWN" }

        service.reconcileBatch()

        assertThat(status(orderId)).isEqualTo("APPROVED")
        assertThat(resultEvents(orderId)).containsExactly("PaymentApproved")
        assertThat(pg.calls[orderId]!!.get()).isEqualTo(1) // 조회로 끝났으니 승인은 다시 보내지 않았다
    }

    @Test
    fun `S5 PG에 요청이 닿지 않았으면 같은 멱등키로 다시 승인해 확정한다`() {
        val orderId = UUID.randomUUID()
        publishOrder(orderId, 300_013)
        eventually("UNKNOWN") { status(orderId) == "UNKNOWN" }

        service.reconcileBatch()

        assertThat(status(orderId)).isEqualTo("APPROVED")
        assertThat(pg.recorded[paymentId(orderId)]).isInstanceOf(PgOutcome.Approved::class.java)
        assertThat(resultEvents(orderId)).containsExactly("PaymentApproved")
    }

    @Test
    fun `PG가 계속 대답하지 못하면 실패로 단정하지 않고 UNKNOWN으로 남긴다`() {
        val orderId = UUID.randomUUID()
        publishOrder(orderId, 300_014)
        eventually("UNKNOWN") { status(orderId) == "UNKNOWN" }

        service.reconcileBatch()
        service.reconcileBatch()

        assertThat(status(orderId)).isEqualTo("UNKNOWN")
        assertThat(resultEvents(orderId)).isEmpty()
        assertThat(attempts(orderId)).isGreaterThanOrEqualTo(2)
    }

    @Test
    fun `처리 중 죽어 REQUESTED에 오래 머문 결제도 재조회가 확정한다`() {
        val orderId = UUID.randomUUID()
        val paymentId = UUID.randomUUID()
        jdbc.sql(
            "INSERT INTO payments (id, order_id, partner_id, amount, status, updated_at) VALUES (:id, :orderId, 'P-1', 300000, 'REQUESTED', now() - interval '1 minute')",
        ).param("id", paymentId).param("orderId", orderId).update()

        service.reconcileBatch()

        assertThat(status(orderId)).isEqualTo("APPROVED")
        assertThat(resultEvents(orderId)).containsExactly("PaymentApproved")
    }

    @Test
    fun `재조회가 동시에 두 번 돌아도 결과 이벤트는 한 번이다`() {
        val orderId = UUID.randomUUID()
        publishOrder(orderId, 300_012)
        eventually("UNKNOWN") { status(orderId) == "UNKNOWN" }

        listOf(Thread { service.reconcileBatch() }, Thread { service.reconcileBatch() }).onEach { it.start() }.forEach { it.join() }

        assertThat(status(orderId)).isEqualTo("APPROVED")
        assertThat(resultEvents(orderId)).containsExactly("PaymentApproved")
    }

    @Test
    fun `PG가 받지 않으면(503·서킷 열림) 실패로 끝내지 않고 보류했다가 재조회가 같은 멱등키로 다시 보내 확정한다`() {
        val orderId = UUID.randomUUID()
        publishOrder(orderId, 300_015)
        eventually("보류") { reason(orderId) == "PG_UNAVAILABLE" }
        assertThat(status(orderId)).isEqualTo("REQUESTED")
        assertThat(resultEvents(orderId)).isEmpty() // 보류 중에는 주문을 취소시키지 않는다

        service.reconcileBatch()

        assertThat(status(orderId)).isEqualTo("APPROVED")
        assertThat(resultEvents(orderId)).containsExactly("PaymentApproved")
    }

    @Test
    fun `보류가 기한을 넘기고 PG에도 없으면 다시 보내지 않고 PG_UNAVAILABLE로 실패시킨다`() {
        val orderId = UUID.randomUUID()
        jdbc.sql(
            """
            INSERT INTO payments (id, order_id, partner_id, amount, status, failure_reason, created_at, updated_at)
            VALUES (:id, :orderId, 'P-1', 300000, 'REQUESTED', 'PG_UNAVAILABLE', now() - interval '1 hour', now() - interval '1 minute')
            """.trimIndent(),
        ).param("id", UUID.randomUUID()).param("orderId", orderId).update()

        service.reconcileBatch()

        assertThat(status(orderId)).isEqualTo("FAILED")
        assertThat(reason(orderId)).isEqualTo("PG_UNAVAILABLE")
        assertThat(resultEvents(orderId)).containsExactly("PaymentFailed")
        assertThat(pg.calls[orderId]).isNull() // 기한이 지난 결제는 PG에 다시 보내지 않는다
    }

    private fun publishRefundFixed(orderId: UUID, amount: Long) {
        val payload = RefundAmountFixed(UUID.randomUUID(), orderId, "P-1", amount, 300_000 - amount)
        val envelope = EventEnvelope(UUID.randomUUID(), "RefundAmountFixed", 1, Instant.now(), orderId.toString(), mapper.valueToTree(payload))
        kafka.send(Topics.BUDGET, orderId.toString(), mapper.writeValueAsString(envelope)).get()
    }

    private fun refund(orderId: UUID): Pair<String, Long>? =
        jdbc.sql("SELECT status, amount FROM refunds WHERE order_id = :id").param("id", orderId)
            .query { rs, _ -> rs.getString("status") to rs.getLong("amount") }.optional().orElse(null)

    @Test
    fun `환불 금액이 확정되면 PG에서 그만큼 취소하고, 같은 이벤트가 다시 와도 취소와 RefundCompleted는 한 번이다`() {
        val orderId = UUID.randomUUID()
        publishOrder(orderId, 300_000)
        eventually("APPROVED") { status(orderId) == "APPROVED" }

        publishRefundFixed(orderId, 120_000)
        publishRefundFixed(orderId, 120_000) // 재전달(다른 eventId로 다시 와도 refunds 유니크가 막는다)
        eventually("환불 완료") { refund(orderId)?.first == "COMPLETED" }
        Thread.sleep(1_000)

        assertThat(refund(orderId)).isEqualTo("COMPLETED" to 120_000L)
        val refundId = jdbc.sql("SELECT id FROM refunds WHERE order_id = :id").param("id", orderId).query(UUID::class.java).single()
        assertThat(pg.cancelled[refundId]).isEqualTo(120_000) // 멱등키 = 환불 ID
        assertThat(resultEvents(orderId).filter { it == "RefundCompleted" }).hasSize(1)
    }

    @Test
    fun `결제 금액보다 큰 환불이나 승인되지 않은 결제는 환불하지 않는다`() {
        val approved = UUID.randomUUID()
        publishOrder(approved, 300_000)
        eventually("APPROVED") { status(approved) == "APPROVED" }
        val declined = UUID.randomUUID()
        publishOrder(declined, 300_001)
        eventually("FAILED") { status(declined) == "FAILED" }

        publishRefundFixed(approved, 300_001)
        publishRefundFixed(declined, 1_000)
        Thread.sleep(2_000)
        assertThat(refund(approved)).isNull()
        assertThat(refund(declined)).isNull()
    }

    private fun reason(orderId: UUID): String? =
        jdbc.sql("SELECT failure_reason FROM payments WHERE order_id = :id").param("id", orderId).query(String::class.java).optional().orElse(null)

    private fun paymentId(orderId: UUID): UUID =
        jdbc.sql("SELECT id FROM payments WHERE order_id = :id").param("id", orderId).query(UUID::class.java).single()

    private fun attempts(orderId: UUID): Int =
        jdbc.sql("SELECT reconcile_attempts FROM payments WHERE order_id = :id").param("id", orderId).query(Int::class.java).single()
}

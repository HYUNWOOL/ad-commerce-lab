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

@SpringBootTest
@Import(InfraContainers::class, PaymentFlowTest.StubPgConfig::class)
class PaymentFlowTest {

    /** 주문 금액으로 결과를 정한다: 끝자리 1 → 거절, 2 → 타임아웃(결과 모름), 그 외 승인. */
    class StubPg : PgClient {
        val calls = ConcurrentHashMap<UUID, AtomicInteger>()
        override fun approve(paymentId: UUID, orderId: UUID, amount: Long): PgOutcome {
            calls.computeIfAbsent(orderId) { AtomicInteger() }.incrementAndGet()
            return when (amount % 10) {
                1L -> PgOutcome.Declined("LIMIT_EXCEEDED")
                2L -> PgOutcome.Unknown
                else -> PgOutcome.Approved("pg-$paymentId")
            }
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
}

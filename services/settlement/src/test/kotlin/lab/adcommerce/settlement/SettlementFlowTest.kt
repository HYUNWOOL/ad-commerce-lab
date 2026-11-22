package lab.adcommerce.settlement

import lab.adcommerce.common.EventEnvelope
import lab.adcommerce.common.test.InfraContainers
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.client.RestClient
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.util.UUID

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(InfraContainers::class)
class SettlementFlowTest {
    @LocalServerPort var port = 0
    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var kafka: KafkaTemplate<String, String>
    @Autowired lateinit var mapper: JsonMapper
    @Autowired lateinit var tx: TransactionTemplate

    private val http by lazy {
        RestClient.builder().baseUrl("http://localhost:$port").defaultStatusHandler({ true }) { _, _ -> }.build()
    }

    private fun publish(topic: String, type: String, key: String, payload: Any, eventId: UUID = UUID.randomUUID()): UUID {
        val envelope = EventEnvelope(eventId, type, 1, Instant.now(), key, mapper.valueToTree(payload))
        kafka.send(topic, key, mapper.writeValueAsString(envelope)).get()
        return eventId
    }

    private fun approved(partner: String, amount: Long, eventId: UUID = UUID.randomUUID()): UUID {
        val orderId = UUID.randomUUID()
        return publish(Topics.PAYMENT_RESULT, "PaymentApproved", orderId.toString(), PaymentApproved(orderId, UUID.randomUUID(), partner, amount, "pg-1"), eventId)
    }

    private fun charged(partner: String, amount: Long): UUID {
        val campaignId = UUID.randomUUID()
        return publish(Topics.CHARGE, "CampaignCharged", campaignId.toString(), CampaignCharged(campaignId, partner, UUID.randomUUID(), amount / 30, amount, amount))
    }

    private fun lines(eventId: UUID): List<Triple<String, Long, Long>> =
        jdbc.sql("SELECT l.account, l.debit, l.credit FROM journal_lines l JOIN journal_entries e ON e.id = l.entry_id WHERE e.source_event_id = :id ORDER BY l.id")
            .param("id", eventId).query { rs, _ -> Triple(rs.getString("account"), rs.getLong("debit"), rs.getLong("credit")) }.list()

    private fun statement(partner: String, month: YearMonth): JsonNode =
        mapper.readTree(http.get().uri("/settlements?month={m}", month.toString()).header("X-Partner-Id", partner).retrieve().body(String::class.java))

    private fun eventually(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos()
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(200)
        }
        throw AssertionError("20초 안에 만족하지 않음: $what")
    }

    @Test
    fun `결제 승인은 PG 미수금 차변, 파트너 선수금 대변으로 기록한다`() {
        val eventId = approved("P-A", 300_000)
        eventually("분개") { lines(eventId).size == 2 }
        assertThat(lines(eventId)).containsExactly(Triple("PG_RECEIVABLE", 300_000L, 0L), Triple("PARTNER_PREPAID", 0L, 300_000L))
    }

    @Test
    fun `노출 과금은 선수금 차변, 광고 매출 대변으로 기록한다`() {
        val eventId = charged("P-A", 990)
        eventually("분개") { lines(eventId).size == 2 }
        assertThat(lines(eventId)).containsExactly(Triple("PARTNER_PREPAID", 990L, 0L), Triple("AD_REVENUE", 0L, 990L))
    }

    @Test
    fun `같은 이벤트를 두 번 받아도 원장에는 한 번만 기록한다`() {
        val eventId = UUID.randomUUID()
        approved("P-A", 100_000, eventId)
        approved("P-A", 100_000, eventId)
        eventually("분개") { lines(eventId).size == 2 }
        Thread.sleep(1_000)
        assertThat(jdbc.sql("SELECT count(*) FROM journal_entries WHERE source_event_id = :id").param("id", eventId).query(Int::class.java).single()).isEqualTo(1)
    }

    @Test
    fun `월 정산서는 결제액, 사용액, 잔액을 원장에서 계산하고 다른 파트너의 숫자는 섞지 않는다`() {
        val partner = "P-" + UUID.randomUUID()
        val payment = approved(partner, 300_000)
        val charge1 = charged(partner, 990)
        val charge2 = charged(partner, 5_010)
        charged("P-OTHER", 7_000)
        eventually("분개 3개") { listOf(payment, charge1, charge2).all { lines(it).size == 2 } }

        val thisMonth = YearMonth.now(ZoneId.of("Asia/Seoul"))
        val s = statement(partner, thisMonth)
        assertThat(s.get("paid").asLong()).isEqualTo(300_000)
        assertThat(s.get("used").asLong()).isEqualTo(6_000)
        assertThat(s.get("balance").asLong()).isEqualTo(294_000)
        assertThat(statement(partner, thisMonth.minusMonths(1)).get("balance").asLong()).isZero()
    }

    @Test
    fun `환불은 선수금 차변, PG 미수금 대변으로 기록하고 정산서의 환불액과 잔액에 반영한다`() {
        val partner = "P-" + UUID.randomUUID()
        val payment = approved(partner, 300_000)
        val charge = charged(partner, 120_000)
        val orderId = UUID.randomUUID()
        val refund = publish(Topics.PAYMENT_REFUND, "RefundCompleted", orderId.toString(), RefundCompleted(orderId, UUID.randomUUID(), partner, 180_000))
        val zero = publish(Topics.PAYMENT_REFUND, "RefundCompleted", UUID.randomUUID().toString(), RefundCompleted(UUID.randomUUID(), UUID.randomUUID(), partner, 0))
        eventually("분개 3개") { listOf(payment, charge, refund).all { lines(it).size == 2 } }

        assertThat(lines(refund)).containsExactly(Triple("PARTNER_PREPAID", 180_000L, 0L), Triple("PG_RECEIVABLE", 0L, 180_000L))
        assertThat(lines(zero)).isEmpty() // 0원 환불은 적을 것이 없다
        val s = statement(partner, YearMonth.now(ZoneId.of("Asia/Seoul")))
        assertThat(s.get("refunded").asLong()).isEqualTo(180_000)
        assertThat(s.get("balance").asLong()).isZero() // 30만 원 = 사용 12만 + 환불 18만
    }

    @Test
    fun `차변 합과 대변 합이 다른 분개는 DB가 커밋을 막는다`() {
        assertThatThrownBy {
            tx.executeWithoutResult {
                val id = jdbc.sql("INSERT INTO journal_entries (source_event_id, partner_id, kind, occurred_at) VALUES (:id, 'P-X', 'PAYMENT', now()) RETURNING id")
                    .param("id", UUID.randomUUID()).query(Long::class.java).single()
                jdbc.sql("INSERT INTO journal_lines (entry_id, account, partner_id, debit) VALUES (:id, 'PG_RECEIVABLE', 'P-X', 1000)").param("id", id).update()
            }
        }.rootCause().hasMessageContaining("차변 합과 대변 합") // 커밋 때 터진다: TransactionSystemException ← PSQLException
    }

    @Test
    fun `원장은 고치거나 지울 수 없다`() {
        val eventId = approved("P-A", 50_000)
        eventually("분개") { lines(eventId).size == 2 }
        assertThatThrownBy { jdbc.sql("UPDATE journal_lines SET credit = credit + 1 WHERE credit > 0").update() }.hasMessageContaining("고치거나 지울 수 없습니다")
        assertThatThrownBy { jdbc.sql("DELETE FROM journal_entries").update() }.hasMessageContaining("고치거나 지울 수 없습니다")
    }
}

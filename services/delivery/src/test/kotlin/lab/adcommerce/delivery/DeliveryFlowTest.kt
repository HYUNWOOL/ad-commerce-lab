package lab.adcommerce.delivery

import lab.adcommerce.common.EventEnvelope
import lab.adcommerce.common.test.InfraContainers
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.web.client.RestClient
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(InfraContainers::class)
class DeliveryFlowTest {
    @LocalServerPort var port = 0
    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var kafka: KafkaTemplate<String, String>
    @Autowired lateinit var mapper: JsonMapper

    private val http by lazy {
        RestClient.builder().baseUrl("http://localhost:$port").defaultStatusHandler({ true }) { _, _ -> }.build()
    }

    private fun activate(budget: Long, cpv: Long, partner: String = "P-1"): UUID {
        val campaignId = UUID.randomUUID()
        val payload = CampaignActivated(campaignId, UUID.randomUUID(), partner, budget, cpv, LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 7))
        val envelope = EventEnvelope(UUID.randomUUID(), "CampaignActivated", 1, Instant.now(), campaignId.toString(), mapper.valueToTree(payload))
        kafka.send(Topics.LIFECYCLE, campaignId.toString(), mapper.writeValueAsString(envelope)).get()
        eventually("예산 생성") { jdbc.sql("SELECT count(*) FROM campaign_budgets WHERE campaign_id = :id").param("id", campaignId).query(Int::class.java).single() == 1 }
        return campaignId
    }

    private fun impressions(campaignId: UUID, n: Int) = List(n) { Impression(UUID.randomUUID(), campaignId, "search-top", Instant.now()) }

    private fun post(body: Any): Int =
        http.post().uri("/impressions").contentType(MediaType.APPLICATION_JSON).body(mapper.writeValueAsString(body))
            .retrieve().toBodilessEntity().statusCode.value()

    private fun spent(campaignId: UUID): Long =
        jdbc.sql("SELECT spent FROM campaign_budgets WHERE campaign_id = :id").param("id", campaignId).query(Long::class.java).single()

    private fun received(campaignId: UUID): Int =
        jdbc.sql("SELECT count(*) FROM impression_events WHERE campaign_id = :id").param("id", campaignId).query(Int::class.java).single()

    private fun events(campaignId: UUID, type: String): List<String> =
        jdbc.sql("SELECT payload FROM outbox_events WHERE message_key = :id").param("id", campaignId.toString())
            .query(String::class.java).list().filterNotNull().filter { mapper.readTree(it).get("type").asString() == type }

    private fun eventually(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos()
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(200)
        }
        throw AssertionError("20초 안에 만족하지 않음: $what")
    }

    @Test
    fun `노출을 받으면 단가만큼 과금하고 CampaignCharged로 누적 사용액을 알린다`() {
        val campaignId = activate(300_000, 50)
        assertThat(post(impressions(campaignId, 100))).isEqualTo(202)

        eventually("5,000원 과금") { spent(campaignId) == 5_000L }
        val charged = events(campaignId, "CampaignCharged").map { mapper.readTree(it).get("payload") }
        assertThat(charged.sumOf { it.get("impressions").asLong() }).isEqualTo(100)
        assertThat(charged.maxOf { it.get("spentTotal").asLong() }).isEqualTo(5_000)
    }

    @Test
    fun `S9 같은 노출을 다시 보내도 과금은 한 번이다`() {
        val campaignId = activate(300_000, 50)
        val batch = impressions(campaignId, 100)
        post(batch)
        eventually("첫 과금") { spent(campaignId) == 5_000L }

        post(batch) // 광고 서버가 응답을 못 받고 다시 보낸 경우
        post(batch.take(30) + impressions(campaignId, 10)) // 일부 중복 + 새 노출 10건
        eventually("새 노출 10건만 과금") { spent(campaignId) == 5_500L }
        Thread.sleep(1_000)
        assertThat(spent(campaignId)).isEqualTo(5_500)
        assertThat(received(campaignId)).isEqualTo(110)
    }

    @Test
    fun `S8 예산을 넘겨 과금하지 않고 소진되면 BudgetExhausted를 한 번만 낸다`() {
        val campaignId = activate(1_000, 30) // 33건까지만 과금할 수 있다(990원)
        post(impressions(campaignId, 50))
        eventually("소진") { jdbc.sql("SELECT status FROM campaign_budgets WHERE campaign_id = :id").param("id", campaignId).query(String::class.java).single() == "EXHAUSTED" }
        post(impressions(campaignId, 10))
        eventually("나중 노출도 기록") { received(campaignId) == 60 }

        assertThat(spent(campaignId)).isEqualTo(990)
        assertThat(events(campaignId, "BudgetExhausted")).hasSize(1)
    }

    @Test
    fun `모르는 캠페인의 노출은 기록만 하고 과금하지 않는다`() {
        val unknown = UUID.randomUUID()
        post(impressions(unknown, 5))
        eventually("기록") { received(unknown) == 5 }
        assertThat(jdbc.sql("SELECT count(*) FROM charges WHERE campaign_id = :id").param("id", unknown).query(Int::class.java).single()).isZero()
    }

    @Test
    fun `깨진 메시지가 섞여도 나머지 노출은 과금한다`() {
        val campaignId = activate(300_000, 50)
        kafka.send(Topics.IMPRESSION, campaignId.toString(), "{깨진 메시지").get()
        post(impressions(campaignId, 20))
        eventually("1,000원 과금") { spent(campaignId) == 1_000L }
    }

    @Test
    fun `수집 API는 비었거나 500건을 넘거나 필드가 빠진 요청을 400으로 거절한다`() {
        val campaignId = UUID.randomUUID()
        assertThat(post(emptyList<Impression>())).isEqualTo(400)
        assertThat(post(impressions(campaignId, 501))).isEqualTo(400)
        assertThat(post(listOf(mapOf("eventId" to UUID.randomUUID(), "placement" to "x")))).isEqualTo(400)
    }

    @Test
    fun `집행 통계는 자기 캠페인만 보여준다`() {
        val campaignId = activate(300_000, 50, partner = "P-OWNER")
        val mine = http.get().uri("/campaigns/{id}/stats", campaignId).header("X-Partner-Id", "P-OWNER").retrieve().toEntity(String::class.java)
        val other = http.get().uri("/campaigns/{id}/stats", campaignId).header("X-Partner-Id", "P-OTHER").retrieve().toBodilessEntity()
        assertThat(mine.statusCode.value()).isEqualTo(200)
        assertThat(mapper.readTree(mine.body).get("remaining").asLong()).isEqualTo(300_000)
        assertThat(other.statusCode.value()).isEqualTo(404)
    }
}

package lab.adcommerce.campaign

import lab.adcommerce.common.DependencyUnavailable
import lab.adcommerce.common.EventEnvelope
import lab.adcommerce.common.test.InfraContainers
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.serialization.StringDeserializer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.web.client.RestClient
import org.testcontainers.kafka.KafkaContainer
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.Instant
import java.util.UUID

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(InfraContainers::class, CampaignFlowTest.StubCatalog::class)
class CampaignFlowTest {

    class FakeCatalog : CatalogClient {
        @Volatile var down = false
        override fun product(id: Long): Product {
            if (down) throw DependencyUnavailable("catalog")
            if (id == 99L) throw ProductNotFound(id)
            return Product(id, "SEARCH_TOP_7D", "검색 상단 7일", 50, 100_000, 7, true)
        }
    }

    @TestConfiguration
    class StubCatalog {
        @Bean @Primary fun fakeCatalog() = FakeCatalog()
    }

    @LocalServerPort var port = 0
    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var kafka: KafkaTemplate<String, String>
    @Autowired lateinit var mapper: JsonMapper
    @Autowired lateinit var catalog: FakeCatalog
    @Autowired lateinit var kafkaContainer: KafkaContainer

    private val http by lazy {
        RestClient.builder().baseUrl("http://localhost:$port").defaultStatusHandler({ true }) { _, _ -> }.build()
    }

    private fun order(partner: String, key: String, budget: Long = 300_000, productId: Long = 1): ResponseEntity<String> =
        http.post().uri("/orders")
            .header("X-Partner-Id", partner).header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"productId":$productId,"budget":$budget,"startDate":"2026-12-01","endDate":"2026-12-07"}""")
            .retrieve().toEntity(String::class.java)

    private fun orderId(response: ResponseEntity<String>): String = mapper.readTree(response.body).get("id").asString()

    private fun publish(type: String, orderId: String, payload: Any): UUID {
        val envelope = EventEnvelope(UUID.randomUUID(), type, 1, Instant.now(), orderId, mapper.valueToTree(payload))
        kafka.send("payment.result", orderId, mapper.writeValueAsString(envelope)).get()
        return envelope.eventId
    }

    private fun eventually(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos()
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(200)
        }
        throw AssertionError("20초 안에 만족하지 않음: $what")
    }

    private fun count(sql: String, id: String): Int =
        jdbc.sql(sql).param("id", UUID.fromString(id)).query(Int::class.java).single()

    @Test
    fun `S1 주문을 받으면 202로 접수하고 OrderPlaced를 주문 ID 키로 발행한다`() {
        val response = order("P-S1", UUID.randomUUID().toString())

        assertThat(response.statusCode.value()).isEqualTo(202)
        val id = orderId(response)
        val event = consumeOne("campaign.order", id)
        val envelope = mapper.readValue(event, EventEnvelope::class.java)
        assertThat(envelope.type).isEqualTo("OrderPlaced")
        assertThat(envelope.payload.get("amount").asLong()).isEqualTo(300_000)
        assertThat(envelope.payload.get("cpvPrice").asLong()).isEqualTo(50)
    }

    @Test
    fun `S2 같은 멱등키로 10번 보내면 주문은 하나이고 처음 응답을 그대로 돌려준다`() {
        val key = UUID.randomUUID().toString()
        val responses = (1..10).map { order("P-S2", key) }

        assertThat(responses.first().statusCode.value()).isEqualTo(202)
        assertThat(responses.drop(1).map { it.statusCode.value() }).containsOnly(200)
        assertThat(responses.map { orderId(it) }.toSet()).hasSize(1)
        assertThat(jdbc.sql("SELECT count(*) FROM ad_orders WHERE partner_id = 'P-S2'").query(Int::class.java).single()).isEqualTo(1)
    }

    @Test
    fun `같은 멱등키에 다른 본문이면 409`() {
        val key = UUID.randomUUID().toString()
        order("P-409", key, budget = 300_000)

        assertThat(order("P-409", key, budget = 400_000).statusCode.value()).isEqualTo(409)
    }

    @Test
    fun `최소 예산보다 적거나 없는 상품이면 400`() {
        assertThat(order("P-400", UUID.randomUUID().toString(), budget = 50_000).statusCode.value()).isEqualTo(400)
        assertThat(order("P-400", UUID.randomUUID().toString(), productId = 99).statusCode.value()).isEqualTo(400)
    }

    @Test
    fun `결제 승인 이벤트가 두 번 와도 캠페인은 하나만 만들어진다`() {
        val id = orderId(order("P-PAID", UUID.randomUUID().toString()))
        val approved = PaymentApproved(UUID.fromString(id), UUID.randomUUID(), 300_000, "pg-tx-1")

        publish("PaymentApproved", id, approved)
        publish("PaymentApproved", id, approved) // 다른 eventId로 다시 와도(재발행) 주문 상태 전이가 막는다

        eventually("주문 PAID") { jdbc.sql("SELECT status FROM ad_orders WHERE id = :id").param("id", UUID.fromString(id)).query(String::class.java).single() == "PAID" }
        Thread.sleep(1000)
        assertThat(count("SELECT count(*) FROM campaigns WHERE order_id = :id", id)).isEqualTo(1)
        assertThat(jdbc.sql("SELECT count(*) FROM outbox_events WHERE topic = 'campaign.lifecycle' AND payload LIKE :p").param("p", "%$id%").query(Int::class.java).single()).isEqualTo(1)
    }

    @Test
    fun `S3 결제가 실패하면 주문을 취소한다(Saga 보상)`() {
        val id = orderId(order("P-S3", UUID.randomUUID().toString()))

        publish("PaymentFailed", id, PaymentFailed(UUID.fromString(id), UUID.randomUUID(), "DECLINED"))

        eventually("주문 CANCELLED") {
            jdbc.sql("SELECT status FROM ad_orders WHERE id = :id").param("id", UUID.fromString(id)).query(String::class.java).single() == "CANCELLED"
        }
        assertThat(count("SELECT count(*) FROM campaigns WHERE order_id = :id", id)).isZero()
    }

    @Test
    fun `catalog를 쓸 수 없으면 Retry-After가 붙은 503을 돌려준다`() {
        catalog.down = true
        try {
            val response = order("P-503", UUID.randomUUID().toString())
            assertThat(response.statusCode.value()).isEqualTo(503)
            assertThat(response.headers.getFirst("Retry-After")).isEqualTo("10")
        } finally {
            catalog.down = false
        }
    }

    @Test
    fun `주문 상태는 허용된 방향으로만 바뀐다`() {
        assertThat(OrderStatus.PAYMENT_PENDING.canMoveTo(OrderStatus.PAID)).isTrue()
        assertThat(OrderStatus.PAYMENT_PENDING.canMoveTo(OrderStatus.CANCELLED)).isTrue()
        assertThat(OrderStatus.CANCELLED.canMoveTo(OrderStatus.PAID)).isFalse()
        assertThat(OrderStatus.PAID.canMoveTo(OrderStatus.CANCELLED)).isFalse()
    }

    private fun consumeOne(topic: String, key: String): String {
        val props = mapOf(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to kafkaContainer.bootstrapServers,
            ConsumerConfig.GROUP_ID_CONFIG to "test-${UUID.randomUUID()}",
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java,
        )
        KafkaConsumer<String, String>(props).use { consumer ->
            consumer.subscribe(listOf(topic))
            val deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos()
            while (System.nanoTime() < deadline) {
                consumer.poll(Duration.ofMillis(500)).firstOrNull { it.key() == key }?.let { return it.value() }
            }
        }
        throw AssertionError("$topic 에서 키 $key 메시지를 받지 못함")
    }
}

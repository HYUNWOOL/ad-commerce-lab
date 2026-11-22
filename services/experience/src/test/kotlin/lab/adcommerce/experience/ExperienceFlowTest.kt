package lab.adcommerce.experience

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import lab.adcommerce.common.test.InfraContainers
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.web.client.RestClient
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import java.net.InetSocketAddress
import java.util.concurrent.Executors

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(InfraContainers::class)
class ExperienceFlowTest {

    companion object {
        @Volatile var deliveryDelayMs = 0L
        @Volatile var settlementStatus = 200

        private const val PRODUCTS = """[{"id":1,"code":"SEARCH_TOP_7D","name":"검색 상단 7일","cpvPrice":50,"minBudget":100000,"maxDays":7,"active":true},
            {"id":4,"code":"EVENT_SPOTLIGHT","name":"이벤트","cpvPrice":80,"minBudget":500000,"maxDays":3,"active":false}]"""
        private const val STATS = """[{"campaignId":"c-1","budget":300000,"spent":75000,"remaining":225000,"cpvPrice":50,"chargedImpressions":1500,"status":"ACTIVE"},
            {"campaignId":"c-2","budget":100000,"spent":100000,"remaining":0,"cpvPrice":50,"chargedImpressions":2000,"status":"EXHAUSTED"}]"""
        private const val SETTLEMENT = """{"partnerId":"P-1","month":"2026-11","paid":400000,"used":175000,"refunded":0,"balance":225000}"""

        /** 하위 서비스(catalog·delivery·settlement) 흉내. 실제 HTTP라 타임아웃·서킷도 진짜로 지난다. */
        private val stub: HttpServer = HttpServer.create(InetSocketAddress(0), 0).apply {
            executor = Executors.newVirtualThreadPerTaskExecutor()
            createContext("/products") { respond(it, 200, PRODUCTS) }
            createContext("/campaigns/stats") { Thread.sleep(deliveryDelayMs); respond(it, 200, STATS) }
            createContext("/settlements") { respond(it, settlementStatus, SETTLEMENT) }
            start()
        }

        private fun respond(exchange: HttpExchange, status: Int, body: String) = runCatching {
            val bytes = body.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }

        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            val url = "http://localhost:${stub.address.port}"
            listOf("catalog", "delivery", "settlement").forEach { registry.add("clients.$it.url") { url } }
        }

        const val NEW_CLIENT = "Banner@1,SettlementSummary@1,BudgetGauge@1,CampaignPerformanceList@2,ActionButton@1,ProductList@1,PurchaseForm@1,Fallback@1"
        const val OLD_CLIENT = "Banner@1,SettlementSummary@1,CampaignPerformanceList@1,ActionButton@1,ProductList@1,PurchaseForm@1,Fallback@1"
    }

    @LocalServerPort var port = 0
    @Autowired lateinit var mapper: JsonMapper

    private val http by lazy { RestClient.builder().baseUrl("http://localhost:$port").defaultStatusHandler({ true }) { _, _ -> }.build() }

    @AfterEach
    fun heal() {
        deliveryDelayMs = 0
        settlementStatus = 200
    }

    private fun screen(id: String, components: String? = NEW_CLIENT): JsonNode =
        mapper.readTree(
            http.get().uri("/screens/{id}", id).header("X-Partner-Id", "P-1")
                .apply { if (components != null) header("X-Client-Components", components) }
                .retrieve().body(String::class.java),
        )

    private fun section(screen: JsonNode, id: String): JsonNode = screen.get("sections").first { it.get("id").asString() == id }

    private fun admin(method: String, path: String, body: String? = null): Pair<Int, JsonNode?> {
        val spec = if (method == "PUT") http.put().uri(path).contentType(MediaType.APPLICATION_JSON).body(body!!) else http.get().uri(path)
        val response = spec.header("X-Operator-Id", "ops-1").retrieve().toEntity(String::class.java)
        return response.statusCode.value() to response.body?.let { mapper.readTree(it) }
    }

    private fun saveBody(revision: Int, title: String, sections: List<JsonNode>): String =
        mapper.writeValueAsString(
            mapper.createObjectNode().put("baseRevision", revision)
                .set("layout", mapper.createObjectNode().put("title", title).set("sections", mapper.createArrayNode().addAll(sections))),
        )

    @Test
    fun `홈 화면은 설정 순서대로 섹션을 내려주고 출처 데이터를 props로 채운다`() {
        val home = screen("home")
        assertThat(home.get("sections").values().map { it.get("id").asString() }).containsExactly("notice", "settlement", "gauge", "performance", "cta")
        assertThat(section(home, "settlement").get("props").get("balance").asLong()).isEqualTo(225_000)
        assertThat(section(home, "gauge").get("props").get("spent").asLong()).isEqualTo(175_000)
        val first = section(home, "performance").get("props").get("items").first()
        assertThat(first.get("progressBp").asLong()).isEqualTo(2_500) // 75,000 / 300,000 = 25.00%
        assertThat(first.get("chargedImpressions").asLong()).isEqualTo(1_500)
    }

    @Test
    fun `S11 구버전 클라이언트에는 모르는 컴포넌트만 대체 표시로, 아는 컴포넌트는 그 버전에 맞춰 내려준다`() {
        val home = screen("home", OLD_CLIENT)
        val gauge = section(home, "gauge")
        assertThat(gauge.get("type").asString()).isEqualTo("Fallback")
        assertThat(gauge.get("props").get("reason").asString()).isEqualTo("UNSUPPORTED_COMPONENT")
        assertThat(gauge.get("props").get("message").asString()).isEqualTo("앱을 업데이트하면 예산 사용률을 볼 수 있어요")
        val performance = section(home, "performance")
        assertThat(performance.get("version").asInt()).isEqualTo(1)
        assertThat(performance.get("props").get("items").first().has("progressBp")).isFalse()
        assertThat(section(home, "settlement").get("type").asString()).isEqualTo("SettlementSummary")
    }

    @Test
    fun `S12 delivery가 느리면 그 출처를 쓰는 영역만 대체 표시하고 화면은 제때 내려준다`() {
        deliveryDelayMs = 3_000
        val started = System.nanoTime()
        val home = screen("home")
        val tookMs = (System.nanoTime() - started) / 1_000_000

        assertThat(listOf("gauge", "performance").map { section(home, it).get("props").get("reason").asString() }).containsOnly("SOURCE_UNAVAILABLE")
        assertThat(section(home, "settlement").get("type").asString()).isEqualTo("SettlementSummary")
        assertThat(section(home, "notice").get("type").asString()).isEqualTo("Banner")
        assertThat(tookMs).isLessThan(2_000) // 응답 타임아웃 1초 + 여유. delivery의 3초를 기다리지 않는다
    }

    @Test
    fun `settlement가 500이면 정산 영역만 대체 표시한다`() {
        settlementStatus = 500
        val home = screen("home")
        assertThat(section(home, "settlement").get("type").asString()).isEqualTo("Fallback")
        assertThat(section(home, "performance").get("type").asString()).isEqualTo("CampaignPerformanceList")
    }

    @Test
    fun `S10 운영자가 문구와 순서를 바꾸면 배포 없이 다음 요청부터 반영되고, 낡은 리비전으로는 덮어쓰지 못한다`() {
        val config = admin("GET", "/admin/screens/products").second!!
        val revision = config.get("revision").asInt()
        val sections = config.get("layout").get("sections").toList()
        val refund = sections.first { it.get("id").asString() == "refund" } as ObjectNode
        (refund.get("props") as ObjectNode).put("text", "환불 기준이 바뀌었습니다")
        val body = saveBody(revision, config.get("layout").get("title").asString(), listOf(sections[1], sections[2], refund)) // 환불 안내를 맨 아래로

        val (status, saved) = admin("PUT", "/admin/screens/products", body)
        assertThat(status).isEqualTo(200)
        assertThat(saved!!.get("revision").asInt()).isEqualTo(revision + 1)

        val products = screen("products")
        assertThat(products.get("revision").asInt()).isEqualTo(revision + 1)
        assertThat(products.get("sections").values().map { it.get("id").asString() }).containsExactly("products", "purchase", "refund")
        assertThat(section(products, "refund").get("props").get("text").asString()).isEqualTo("환불 기준이 바뀌었습니다")
        assertThat(section(products, "products").get("props").get("items")).hasSize(1) // 판매 중지 상품은 빠진다

        assertThat(admin("PUT", "/admin/screens/products", body).first).isEqualTo(409) // 같은 baseRevision으로 또 저장
    }

    @Test
    fun `잘못된 설정은 저장하지 않는다`() {
        val revision = admin("GET", "/admin/screens/home").second!!.get("revision").asInt()
        fun put(sections: String) = admin("PUT", "/admin/screens/home", """{"baseRevision":$revision,"layout":{"title":"내 광고","sections":$sections}}""").first
        assertThat(put("""[{"id":"x","type":"Carousel","version":1,"props":{}}]""")).isEqualTo(400) // 모르는 컴포넌트
        assertThat(put("""[{"id":"x","type":"Banner","version":1,"props":{"text":""}}]""")).isEqualTo(400) // 필수 문구 없음
        assertThat(put("""[{"id":"x","type":"CampaignPerformanceList","version":3,"props":{"title":"성과"}}]""")).isEqualTo(400) // 없는 버전
        assertThat(put("""[{"id":"a","type":"Banner","version":1,"props":{"text":"1"}},{"id":"a","type":"Banner","version":1,"props":{"text":"2"}}]""")).isEqualTo(400) // id 중복
        assertThat(admin("GET", "/admin/screens/home").second!!.get("revision").asInt()).isEqualTo(revision)
    }

    @Test
    fun `숨긴 섹션은 내려주지 않는다`() {
        val config = admin("GET", "/admin/screens/home").second!!
        val sections = config.get("layout").get("sections").values().map { s ->
            (s as ObjectNode).also { if (it.get("id").asString() == "cta") it.put("visible", false) }
        }
        assertThat(admin("PUT", "/admin/screens/home", saveBody(config.get("revision").asInt(), "내 광고", sections)).first).isEqualTo(200)
        assertThat(screen("home").get("sections").values().map { it.get("id").asString() }).doesNotContain("cta")

        // 다른 테스트가 같은 DB를 쓰므로 되돌린다(되돌리기도 새 리비전이다)
        sections.forEach { it.put("visible", true) }
        assertThat(admin("PUT", "/admin/screens/home", saveBody(config.get("revision").asInt() + 1, "내 광고", sections)).first).isEqualTo(200)
    }
}

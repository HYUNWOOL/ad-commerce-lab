package lab.adcommerce.experience

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import io.micrometer.core.instrument.MeterRegistry
import lab.adcommerce.common.NotFound
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.dao.DuplicateKeyException
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.client.RestClient
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.http.HttpClient
import java.time.Duration
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@SpringBootApplication(scanBasePackages = ["lab.adcommerce"])
class ExperienceApplication

fun main(args: Array<String>) {
    runApplication<ExperienceApplication>(*args)
}

/** 화면 설정(리비전 하나). 섹션 순서·문구·노출 여부를 운영자가 바꾼다(ADR-0009 1·2번). */
data class Layout(val title: String, val sections: List<SectionSpec>)

data class SectionSpec(
    val id: String,
    val type: String,
    val version: Int,
    val visible: Boolean = true,
    val props: Map<String, Any?> = emptyMap(),
    /** 클라이언트가 이 컴포넌트를 모를 때 보여 줄 문구. */
    val fallbackText: String? = null,
)

/** 클라이언트에 내려가는 화면. 클라이언트는 섹션을 순서대로 그리기만 한다. */
data class Screen(val screenId: String, val revision: Int, val title: String, val generatedAt: Instant, val sections: List<Section>)

data class Section(val id: String, val type: String, val version: Int, val props: Map<String, Any?>)

/** 데이터를 가져올 하위 서비스. 화면 하나가 쓰는 출처를 병렬로 부른다. */
enum class Source(val service: String) { SETTLEMENT("settlement"), CAMPAIGN_STATS("delivery"), PRODUCTS("catalog"), APPROVALS("agent") }

/** 서버가 아는 컴포넌트. required는 설정에 꼭 있어야 하는 문구(저장할 때 검사). */
enum class UiComponent(val maxVersion: Int, val source: Source?, val required: List<String> = emptyList()) {
    Banner(1, null, listOf("text")),
    SettlementSummary(1, Source.SETTLEMENT, listOf("title")),
    BudgetGauge(1, Source.CAMPAIGN_STATS, listOf("title")),
    CampaignPerformanceList(2, Source.CAMPAIGN_STATS, listOf("title")),
    ActionButton(1, null, listOf("label", "screen")),
    ProductList(1, Source.PRODUCTS, listOf("title")),
    PurchaseForm(1, null, listOf("title", "submitLabel")),
    /** 에이전트가 만든 승인 요청(ADR-0010 4번). 승인·거절 버튼은 사람이 누른다. */
    ApprovalList(1, Source.APPROVALS, listOf("title")),
    Fallback(1, null),
    ;

    companion object {
        fun of(type: String): UiComponent? = entries.firstOrNull { it.name == type }
    }
}

/**
 * 클라이언트가 아는 컴포넌트와 최고 버전. 헤더 예: `Banner@1,CampaignPerformanceList@2`.
 * 헤더가 없으면 서버가 아는 최신 버전을 모두 안다고 본다.
 */
class ClientComponents private constructor(private val versions: Map<String, Int>?) {
    fun versionOf(c: UiComponent): Int? = if (versions == null) c.maxVersion else versions[c.name]

    companion object {
        fun parse(header: String?): ClientComponents = ClientComponents(
            header?.split(',')?.mapNotNull { token ->
                val parts = token.trim().split('@')
                if (parts.size != 2) null else parts[1].toIntOrNull()?.takeIf { it > 0 }?.let { parts[0] to it }
            }?.toMap(),
        )
    }
}

private val SECTION_ID = Regex("[a-z0-9-]{1,32}")

/** 설정 실수로 화면이 깨지지 않게 저장 전에 막는다(400). */
fun Layout.validate() {
    require(title.isNotBlank() && title.length <= 50) { "화면 제목은 1~50자입니다" }
    require(sections.isNotEmpty()) { "섹션이 하나 이상 있어야 합니다" }
    require(sections.map { it.id }.toSet().size == sections.size) { "섹션 id가 겹칩니다" }
    sections.forEach { s ->
        require(SECTION_ID.matches(s.id)) { "섹션 id는 영문 소문자·숫자·-로 1~32자입니다: ${s.id}" }
        val c = UiComponent.of(s.type)?.takeIf { it != UiComponent.Fallback }
            ?: throw IllegalArgumentException("모르는 컴포넌트입니다: ${s.type}")
        require(s.version in 1..c.maxVersion) { "${s.type}의 버전은 1~${c.maxVersion}입니다" }
        c.required.forEach { key ->
            val v = s.props[key]
            require(v is String && v.isNotBlank() && v.length <= 300) { "${s.id}.$key 문구가 필요합니다(1~300자)" }
        }
    }
}

/** 하위 서비스 조회. 출처마다 타임아웃(연결 0.5초, 응답 1초)과 서킷을 둔다(ADR-0003, ADR-0009 3번). */
@Component
class Sources(
    builder: RestClient.Builder,
    registry: CircuitBreakerRegistry,
    @Value("\${clients.catalog.url}") catalogUrl: String,
    @Value("\${clients.delivery.url}") deliveryUrl: String,
    @Value("\${clients.settlement.url}") settlementUrl: String,
    @Value("\${clients.agent.url}") agentUrl: String,
    @Value("\${clients.read-timeout:1s}") readTimeout: Duration,
) {
    private val clients = mapOf("catalog" to catalogUrl, "delivery" to deliveryUrl, "settlement" to settlementUrl, "agent" to agentUrl).mapValues { (_, url) ->
        builder.clone().baseUrl(url)
            .requestFactory(
                JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofMillis(500)).build())
                    .apply { setReadTimeout(readTimeout) },
            )
            .build()
    }
    private val breakers = Source.entries.associateWith { registry.circuitBreaker(it.service) }

    fun load(source: Source, partnerId: String): JsonNode {
        val rest = clients.getValue(source.service)
        return breakers.getValue(source).executeSupplier {
            when (source) {
                Source.SETTLEMENT -> rest.get().uri("/settlements?month={m}", YearMonth.now(SEOUL)).header("X-Partner-Id", partnerId)
                Source.CAMPAIGN_STATS -> rest.get().uri("/campaigns/stats").header("X-Partner-Id", partnerId)
                Source.PRODUCTS -> rest.get().uri("/products")
                Source.APPROVALS -> rest.get().uri("/approvals?status=PENDING").header("X-Partner-Id", partnerId)
            }.retrieve().body(JsonNode::class.java)!!
        }
    }

    companion object {
        private val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")
    }
}

@Component
class ScreenRepository(private val jdbc: JdbcClient, private val mapper: JsonMapper) {

    fun latest(screenId: String): Pair<Int, Layout>? =
        jdbc.sql("SELECT revision, layout::text AS layout FROM screen_revisions WHERE screen_id = :id ORDER BY revision DESC LIMIT 1")
            .param("id", screenId)
            .query { rs, _ -> rs.getInt("revision") to mapper.readValue(rs.getString("layout"), Layout::class.java) }
            .optional().orElse(null)

    /** baseRevision이 지금 최신일 때만 새 리비전을 쌓는다. 그사이 다른 변경이 있었으면 null(덮어쓰기 방지). */
    fun save(screenId: String, baseRevision: Int, layout: Layout, operator: String): Int? = try {
        val inserted = jdbc.sql(
            """
            INSERT INTO screen_revisions (screen_id, revision, layout, created_by)
            SELECT :id, :base + 1, CAST(:layout AS jsonb), :operator
            WHERE (SELECT max(revision) FROM screen_revisions WHERE screen_id = :id) = :base
            """.trimIndent(),
        ).param("id", screenId).param("base", baseRevision).param("layout", mapper.writeValueAsString(layout)).param("operator", operator).update()
        if (inserted == 1) baseRevision + 1 else null
    } catch (e: DuplicateKeyException) {
        null // 같은 baseRevision으로 동시에 저장한 다른 요청이 먼저 들어갔다
    }

    fun revisions(screenId: String): List<Map<String, Any?>> =
        jdbc.sql("SELECT revision, created_by, created_at FROM screen_revisions WHERE screen_id = :id ORDER BY revision DESC")
            .param("id", screenId)
            .query { rs, _ -> mapOf("revision" to rs.getInt("revision"), "createdBy" to rs.getString("created_by"), "createdAt" to rs.getTimestamp("created_at").toInstant()) }
            .list()
}

@Service
class ScreenService(
    private val repo: ScreenRepository,
    private val sources: Sources,
    private val meters: MeterRegistry,
    @Value("\${experience.screen-deadline:1500ms}") private val deadline: Duration,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val executor = Executors.newVirtualThreadPerTaskExecutor()

    fun render(screenId: String, partnerId: String, client: ClientComponents): Screen {
        val (revision, layout) = repo.latest(screenId) ?: throw NotFound("화면 $screenId 이(가) 없습니다")
        val sections = layout.sections.filter { it.visible }
        // 클라이언트가 그릴 수 있는 섹션의 출처만 부른다.
        val wanted = sections.mapNotNull { s -> UiComponent.of(s.type)?.takeIf { client.versionOf(it) != null }?.source }.toSet()
        val started = System.nanoTime()
        val futures = wanted.associateWith { source -> CompletableFuture.supplyAsync({ sources.load(source, partnerId) }, executor) }
        val data: Map<Source, JsonNode?> = futures.mapValues { (source, future) ->
            val left = deadline.toNanos() - (System.nanoTime() - started)
            try {
                future.get(left.coerceAtLeast(0), TimeUnit.NANOSECONDS)
            } catch (e: Exception) {
                future.cancel(true)
                // 마지막 인자가 예외면 SLF4J가 {}에 넣지 않고 스택을 찍는다. 원인 한 줄만 글자로 남긴다.
                log.warn("출처 {} 실패, 그 영역만 대체 표시: {}", source, (e.cause ?: e).toString())
                null
            }
        }
        return Screen(screenId, revision, layout.title, Instant.now(), sections.map { section(it, data, client) })
    }

    private fun section(spec: SectionSpec, data: Map<Source, JsonNode?>, client: ClientComponents): Section {
        val component = UiComponent.of(spec.type) ?: return fallback(spec, "UNSUPPORTED_COMPONENT", "이 영역은 지금 볼 수 없어요")
        val clientVersion = client.versionOf(component)
            ?: return fallback(spec, "UNSUPPORTED_COMPONENT", spec.fallbackText ?: "앱을 업데이트하면 볼 수 있어요")
        val node = component.source?.let { data[it] }
        if (component.source != null && node == null) return fallback(spec, "SOURCE_UNAVAILABLE", "잠시 후 다시 확인해 주세요")
        val version = minOf(spec.version, clientVersion)
        count(spec.type, if (version < spec.version) "downgraded" else "ok")
        return Section(spec.id, spec.type, version, spec.props + shape(component, version, node))
    }

    /** 출처 데이터를 컴포넌트 props로 만든다. 낮은 버전으로 보낼 때는 그 버전에 없는 필드를 뺀다. 금액은 정수(원)로만 다룬다. */
    private fun shape(c: UiComponent, version: Int, node: JsonNode?): Map<String, Any?> = when (c) {
        UiComponent.SettlementSummary -> mapOf(
            "month" to node!!.get("month").asString(), "paid" to node.get("paid").asLong(),
            "used" to node.get("used").asLong(), "balance" to node.get("balance").asLong(),
        )
        UiComponent.BudgetGauge -> {
            val items = node!!.values()
            mapOf("budget" to items.sumOf { it.get("budget").asLong() }, "spent" to items.sumOf { it.get("spent").asLong() }, "campaigns" to items.size)
        }
        UiComponent.CampaignPerformanceList -> mapOf(
            // Jackson 3의 JsonNode.map은 노드 자신에 함수를 적용한다. 원소마다 돌려면 values()를 쓴다.
            "items" to node!!.values().map { s ->
                buildMap {
                    put("campaignId", s.get("campaignId").asString())
                    put("status", s.get("status").asString())
                    put("budget", s.get("budget").asLong())
                    put("spent", s.get("spent").asLong())
                    put("remaining", s.get("remaining").asLong())
                    if (version >= 2) {
                        put("chargedImpressions", s.get("chargedImpressions").asLong())
                        put("progressBp", s.get("spent").asLong() * 10_000 / s.get("budget").asLong()) // 사용률(1만분율)
                    }
                }
            },
        )
        UiComponent.ApprovalList -> mapOf(
            "items" to node!!.values().map { a ->
                val p = a.get("payload")
                mapOf(
                    "id" to a.get("id").asString(), "reason" to a.get("reason").asString(), "createdAt" to a.get("created_at").asString(),
                    "productId" to p.get("product_id").asLong(), "budget" to p.get("budget").asLong(),
                    "startDate" to p.get("start_date").asString(), "endDate" to p.get("end_date").asString(),
                )
            },
        )
        UiComponent.ProductList -> mapOf(
            "items" to node!!.values().filter { it.get("active").asBoolean() }.map { p ->
                mapOf(
                    "id" to p.get("id").asLong(), "code" to p.get("code").asString(), "name" to p.get("name").asString(),
                    "cpvPrice" to p.get("cpvPrice").asLong(), "minBudget" to p.get("minBudget").asLong(), "maxDays" to p.get("maxDays").asInt(),
                )
            },
        )
        else -> emptyMap()
    }

    private fun fallback(spec: SectionSpec, reason: String, message: String): Section {
        count(spec.type, reason.lowercase())
        return Section(spec.id, "Fallback", 1, mapOf("reason" to reason, "message" to message, "title" to spec.props["title"], "replaces" to spec.type))
    }

    private fun count(type: String, outcome: String) = meters.counter("experience.sections", "type", type, "outcome", outcome).increment()
}

@RestController
class ScreenController(private val screens: ScreenService) {
    @GetMapping("/screens/{screenId}")
    fun screen(
        @RequestHeader("X-Partner-Id") partnerId: String,
        @RequestHeader("X-Client-Components", required = false) components: String?,
        @PathVariable screenId: String,
    ): Screen = screens.render(screenId, partnerId, ClientComponents.parse(components))
}

data class SaveScreenRequest(val baseRevision: Int, val layout: Layout)

class StaleRevision(message: String) : RuntimeException(message)

/** 운영자 화면 설정. gateway가 운영자 키에만 X-Operator-Id를 붙인다(ADR-0009 7번). */
@RestController
@RequestMapping("/admin/screens")
class ScreenAdminController(private val repo: ScreenRepository) {
    private val log = LoggerFactory.getLogger(javaClass)

    @GetMapping("/{screenId}")
    fun get(@RequestHeader("X-Operator-Id") operator: String, @PathVariable screenId: String): Map<String, Any> {
        val (revision, layout) = repo.latest(screenId) ?: throw NotFound("화면 $screenId 이(가) 없습니다")
        return mapOf("screenId" to screenId, "revision" to revision, "layout" to layout)
    }

    @PutMapping("/{screenId}")
    fun save(@RequestHeader("X-Operator-Id") operator: String, @PathVariable screenId: String, @RequestBody request: SaveScreenRequest): Map<String, Any> {
        request.layout.validate()
        val revision = repo.save(screenId, request.baseRevision, request.layout, operator)
            ?: if (repo.latest(screenId) == null) {
                throw NotFound("화면 $screenId 이(가) 없습니다")
            } else {
                throw StaleRevision("리비전 ${request.baseRevision} 이후에 다른 변경이 저장됐습니다. 최신 설정을 다시 불러와 주세요")
            }
        log.info("화면 {} 리비전 {} 저장(운영자 {})", screenId, revision, operator)
        return mapOf("screenId" to screenId, "revision" to revision)
    }

    @GetMapping("/{screenId}/revisions")
    fun revisions(@RequestHeader("X-Operator-Id") operator: String, @PathVariable screenId: String) = repo.revisions(screenId)

    @ExceptionHandler(StaleRevision::class)
    fun stale(e: StaleRevision): ProblemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.message)
}

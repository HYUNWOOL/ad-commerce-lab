package lab.adcommerce.delivery

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import lab.adcommerce.common.EventEnvelope
import lab.adcommerce.common.Inbox
import lab.adcommerce.common.NotFound
import lab.adcommerce.common.Outbox
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import tools.jackson.core.JacksonException
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** 캠페인이 켜지면 예산의 원본을 만들고(ADR-0007 3번), 환불이 요청되면 과금을 닫고 환불 금액을 정한다(ADR-0011 2단계). */
@Component
class BudgetListener(private val jdbc: JdbcClient, private val inbox: Inbox, private val outbox: Outbox, private val mapper: JsonMapper) {
    private val log = LoggerFactory.getLogger(javaClass)

    @KafkaListener(topics = [Topics.LIFECYCLE], groupId = "delivery")
    @Transactional
    fun on(message: String) {
        val event = mapper.readValue(message, EventEnvelope::class.java)
        if (event.type !in HANDLED || !inbox.firstTime(event.eventId, "delivery")) return
        when (event.type) {
            "CampaignActivated" -> activate(mapper.treeToValue(event.payload, CampaignActivated::class.java))
            "RefundRequested" -> close(mapper.treeToValue(event.payload, RefundRequested::class.java))
        }
    }

    private fun activate(c: CampaignActivated) {
        jdbc.sql(
            """
            INSERT INTO campaign_budgets (campaign_id, partner_id, budget, cpv_price, status, start_date, end_date)
            VALUES (:id, :partnerId, :budget, :cpv, 'ACTIVE', :start, :end) ON CONFLICT DO NOTHING
            """.trimIndent(),
        ).param("id", c.campaignId).param("partnerId", c.partnerId).param("budget", c.budget).param("cpv", c.cpvPrice)
            .param("start", c.startDate).param("end", c.endDate).update()
    }

    /**
     * 과금과 같은 행을 잠그는 UPDATE 한 문장으로 닫는다. 닫히기 전에 들어온 과금은 사용액에 들어가고,
     * 닫힌 뒤의 노출은 WHERE status = 'ACTIVE'에 걸려 과금되지 않는다. 환불 금액은 이 순간에만 정해진다.
     */
    private fun close(r: RefundRequested) {
        val closed = jdbc.sql(
            """
            UPDATE campaign_budgets SET status = 'CLOSED', updated_at = now()
            WHERE campaign_id = :id AND status IN ('ACTIVE', 'EXHAUSTED')
            RETURNING budget, spent
            """.trimIndent(),
        ).param("id", r.campaignId).query { rs, _ -> rs.getLong("budget") to rs.getLong("spent") }.optional().orElse(null)
        if (closed == null) {
            log.warn("캠페인 {} 의 예산을 닫지 못함(없거나 이미 닫힘)", r.campaignId)
            return
        }
        val (budget, spent) = closed
        outbox.append(
            Topics.BUDGET, "RefundAmountFixed", r.campaignId.toString(),
            RefundAmountFixed(r.campaignId, r.orderId, r.partnerId, refundAmount = budget - spent, spentTotal = spent),
        )
    }

    private companion object {
        val HANDLED = setOf("CampaignActivated", "RefundRequested")
    }
}

/** 노출 배치를 받아 과금한다. 배치 하나가 과금 창 하나다(ADR-0007 2번). */
@Component
class ImpressionBatchListener(private val billing: BillingService, private val mapper: JsonMapper) {
    private val log = LoggerFactory.getLogger(javaClass)

    @KafkaListener(topics = [Topics.IMPRESSION], groupId = "delivery-billing", batch = "true")
    fun on(messages: List<String>) {
        billing.charge(messages.mapNotNull(::parse))
    }

    /** 형식이 깨진 메시지는 그 건만 건너뛴다. 배치 전체를 막지 않는다. */
    private fun parse(message: String): Impression? = try {
        val e = mapper.readValue(message, EventEnvelope::class.java)
        val p = mapper.treeToValue(e.payload, ImpressionReceived::class.java)
        Impression(e.eventId, p.campaignId, p.placement, p.occurredAt)
    } catch (e: JacksonException) {
        log.warn("형식이 깨진 노출 메시지를 건너뜀: {}", e.originalMessage)
        null
    }
}

@Service
class BillingService(private val jdbc: JdbcClient, private val outbox: Outbox, meters: MeterRegistry) {
    private val fresh = meters.counter("delivery.impressions", "result", "fresh")
    private val duplicate = meters.counter("delivery.impressions", "result", "duplicate")
    /** 노출이 일어난 뒤 과금 트랜잭션에 들어오기까지 걸린 시간(목표 p95 2초, m3-plan). */
    private val lag = Timer.builder("delivery.billing.lag").publishPercentileHistogram().register(meters)

    private data class Charged(val partnerId: String, val spent: Long, val status: String, val amount: Long, val cpv: Long)

    @Transactional
    fun charge(impressions: List<Impression>) {
        if (impressions.isEmpty()) return
        val now = Instant.now()
        val firstSeen = recordFirstSeen(impressions)
        fresh.increment(firstSeen.size.toDouble())
        duplicate.increment((impressions.size - firstSeen.size).toDouble())
        firstSeen.forEach { (_, occurredAt) -> lag.record(Duration.between(occurredAt, now)) }
        // 리밸런싱 중 두 컨슈머가 같은 캠페인을 잡아도 교착되지 않게 늘 같은 순서로 잠근다.
        firstSeen.groupingBy { it.first }.eachCount().toSortedMap().forEach { (campaignId, count) -> chargeCampaign(campaignId, count.toLong()) }
    }

    /** 처음 본 노출만 남기고 (캠페인, 발생 시각)을 돌려준다. 이미 받은 eventId는 PK 충돌로 걸러진다(S9). */
    private fun recordFirstSeen(impressions: List<Impression>): List<Pair<UUID, Instant>> =
        jdbc.sql(
            """
            INSERT INTO impression_events (event_id, campaign_id, placement, occurred_at)
            SELECT * FROM unnest(CAST(:ids AS uuid[]), CAST(:campaigns AS uuid[]), CAST(:placements AS text[]), CAST(:times AS timestamptz[]))
            ON CONFLICT (event_id) DO NOTHING
            RETURNING campaign_id, occurred_at
            """.trimIndent(),
        ).param("ids", impressions.map { it.eventId.toString() }.toTypedArray())
            .param("campaigns", impressions.map { it.campaignId.toString() }.toTypedArray())
            .param("placements", impressions.map { it.placement }.toTypedArray())
            .param("times", impressions.map { it.occurredAt.toString() }.toTypedArray())
            .query { rs, _ -> rs.getObject("campaign_id", UUID::class.java) to rs.getTimestamp("occurred_at").toInstant() }
            .list()

    /**
     * 남은 예산 안에서만 한 문장으로 차감한다(S8). SET의 오른쪽은 모두 바뀌기 전 값으로 계산된다.
     * 켜져 있지 않거나 모르는 캠페인은 행이 없어 과금하지 않는다(덜 받는 쪽으로 틀린다).
     */
    private fun chargeCampaign(campaignId: UUID, count: Long) {
        val charged = jdbc.sql(
            """
            WITH cur AS (SELECT campaign_id, spent FROM campaign_budgets WHERE campaign_id = :id AND status = 'ACTIVE' FOR UPDATE)
            UPDATE campaign_budgets b
            SET spent = b.spent + LEAST(:n, (b.budget - b.spent) / b.cpv_price) * b.cpv_price,
                status = CASE WHEN b.budget - b.spent - LEAST(:n, (b.budget - b.spent) / b.cpv_price) * b.cpv_price < b.cpv_price
                              THEN 'EXHAUSTED' ELSE 'ACTIVE' END,
                updated_at = now()
            FROM cur WHERE b.campaign_id = cur.campaign_id
            RETURNING b.partner_id, b.spent, b.status, b.spent - cur.spent AS amount, b.cpv_price
            """.trimIndent(),
        ).param("id", campaignId).param("n", count)
            .query { rs, _ -> Charged(rs.getString("partner_id"), rs.getLong("spent"), rs.getString("status"), rs.getLong("amount"), rs.getLong("cpv_price")) }
            .optional().orElse(null) ?: return

        if (charged.amount > 0) {
            val chargeId = UUID.randomUUID()
            val impressions = charged.amount / charged.cpv
            jdbc.sql("INSERT INTO charges (id, campaign_id, impressions, amount, spent_total) VALUES (:id, :campaignId, :n, :amount, :spent)")
                .param("id", chargeId).param("campaignId", campaignId).param("n", impressions)
                .param("amount", charged.amount).param("spent", charged.spent).update()
            outbox.append(
                Topics.CHARGE, "CampaignCharged", campaignId.toString(),
                CampaignCharged(campaignId, charged.partnerId, chargeId, impressions, charged.amount, charged.spent),
            )
        }
        // WHERE status = 'ACTIVE'로 집었으니 EXHAUSTED가 돌아오면 이번에 막 소진된 것이다. 한 번만 나간다.
        if (charged.status == "EXHAUSTED") {
            outbox.append(Topics.CHARGE, "BudgetExhausted", campaignId.toString(), BudgetExhausted(campaignId, charged.partnerId, charged.spent))
        }
    }
}

data class CampaignStats(
    val campaignId: UUID,
    val budget: Long,
    val spent: Long,
    val remaining: Long,
    val cpvPrice: Long,
    val chargedImpressions: Long,
    val status: String,
)

@RestController
class StatsController(private val jdbc: JdbcClient) {
    private val row = org.springframework.jdbc.core.RowMapper { rs, _ ->
        val budget = rs.getLong("budget")
        val spent = rs.getLong("spent")
        val cpv = rs.getLong("cpv_price")
        CampaignStats(rs.getObject("campaign_id", UUID::class.java), budget, spent, budget - spent, cpv, spent / cpv, rs.getString("status"))
    }

    @GetMapping("/campaigns/{id}/stats")
    fun stats(@RequestHeader("X-Partner-Id") partnerId: String, @PathVariable id: UUID): CampaignStats =
        jdbc.sql("SELECT * FROM campaign_budgets WHERE campaign_id = :id AND partner_id = :partnerId")
            .param("id", id).param("partnerId", partnerId).query(row)
            .optional().orElse(null) ?: throw NotFound("캠페인 $id 의 집행 정보가 없습니다")

    /** 파트너의 최근 캠페인 20개. 화면(experience)이 캠페인마다 부르지 않게 한 번에 준다. */
    @GetMapping("/campaigns/stats")
    fun recent(@RequestHeader("X-Partner-Id") partnerId: String): List<CampaignStats> =
        jdbc.sql("SELECT * FROM campaign_budgets WHERE partner_id = :partnerId ORDER BY created_at DESC LIMIT 20")
            .param("partnerId", partnerId).query(row).list()
}

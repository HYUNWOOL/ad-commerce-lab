package lab.adcommerce.campaign

import lab.adcommerce.common.EventEnvelope
import lab.adcommerce.common.IdempotencyStore
import lab.adcommerce.common.Inbox
import lab.adcommerce.common.NotFound
import lab.adcommerce.common.Outbox
import lab.adcommerce.common.StoredResponse
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

enum class OrderStatus {
    PAYMENT_PENDING, PAID, CANCELLED;

    fun canMoveTo(next: OrderStatus): Boolean = next in (ALLOWED[this] ?: emptySet())

    private companion object {
        val ALLOWED = mapOf(PAYMENT_PENDING to setOf(PAID, CANCELLED))
    }
}

data class PlaceOrderRequest(val productId: Long, val budget: Long, val startDate: LocalDate, val endDate: LocalDate)

data class OrderView(
    val id: UUID,
    val partnerId: String,
    val productId: Long,
    val budget: Long,
    val cpvPrice: Long,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val status: String,
    val failureReason: String?,
)

data class CampaignView(
    val id: UUID,
    val orderId: UUID,
    val productId: Long,
    val budget: Long,
    val spent: Long,
    val cpvPrice: Long,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val status: String,
    val refundedAmount: Long,
)

// 이벤트 페이로드(docs/02-design/03-events.md)
data class OrderPlaced(
    val orderId: UUID, val partnerId: String, val productId: Long, val amount: Long,
    val cpvPrice: Long, val startDate: LocalDate, val endDate: LocalDate,
)
data class PaymentApproved(val orderId: UUID, val paymentId: UUID, val amount: Long, val pgTransactionId: String)
data class PaymentFailed(val orderId: UUID, val paymentId: UUID, val reason: String)
data class CampaignActivated(
    val campaignId: UUID, val orderId: UUID, val partnerId: String, val budget: Long,
    val cpvPrice: Long, val startDate: LocalDate, val endDate: LocalDate,
)
/** delivery가 보내는 과금 이벤트에서 화면에 필요한 값만 읽는다(사용액의 원본은 delivery, ADR-0007). */
data class SpentUpdate(val campaignId: UUID, val spentTotal: Long)

@Service
class OrderService(
    private val jdbc: JdbcClient,
    private val catalog: CatalogClient,
    private val idempotency: IdempotencyStore,
    private val outbox: Outbox,
    private val mapper: JsonMapper,
    private val tx: TransactionTemplate,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun place(partnerId: String, key: String, request: PlaceOrderRequest): StoredResponse {
        val body = mapper.writeValueAsString(request)
        require(request.endDate >= request.startDate) { "종료일이 시작일보다 빠릅니다" }
        // 원격 호출은 DB 트랜잭션 밖에서 한다. 느린 catalog가 DB 커넥션을 붙잡지 않게(P1, ADR-0003).
        val product = catalog.product(request.productId)
        require(product.active) { "판매 중이 아닌 상품입니다" }
        require(request.budget >= product.minBudget) { "최소 예산은 ${product.minBudget}원입니다" }
        val days = ChronoUnit.DAYS.between(request.startDate, request.endDate) + 1
        require(days <= product.maxDays) { "최대 기간은 ${product.maxDays}일입니다" }

        return tx.execute {
            idempotency.execute("order:$partnerId", key, body) {
                val orderId = UUID.randomUUID()
                jdbc.sql(
                    """
                    INSERT INTO ad_orders (id, partner_id, product_id, budget, cpv_price, start_date, end_date, status)
                    VALUES (:id, :partnerId, :productId, :budget, :cpvPrice, :startDate, :endDate, 'PAYMENT_PENDING')
                    """.trimIndent(),
                ).param("id", orderId).param("partnerId", partnerId).param("productId", product.id)
                    .param("budget", request.budget).param("cpvPrice", product.cpvPrice)
                    .param("startDate", request.startDate).param("endDate", request.endDate)
                    .update()
                outbox.append(
                    Topics.ORDER, "OrderPlaced", orderId.toString(),
                    OrderPlaced(orderId, partnerId, product.id, request.budget, product.cpvPrice, request.startDate, request.endDate),
                )
                StoredResponse(202, mapper.writeValueAsString(find(orderId)))
            }
        }!!
    }

    @Transactional(readOnly = true)
    fun find(id: UUID): OrderView? =
        jdbc.sql("SELECT * FROM ad_orders WHERE id = :id").param("id", id).query(OrderView::class.java).optional().orElse(null)

    @Transactional(readOnly = true)
    fun campaigns(partnerId: String): List<CampaignView> =
        jdbc.sql("SELECT * FROM campaigns WHERE partner_id = :partnerId ORDER BY created_at DESC")
            .param("partnerId", partnerId).query(CampaignView::class.java).list().filterNotNull()

    /** 결제 승인 → 주문 PAID, 캠페인 ACTIVE 생성, CampaignActivated 발행. 이미 처리된 주문이면 아무것도 하지 않는다. */
    @Transactional
    fun onPaymentApproved(event: PaymentApproved) {
        if (!transition(event.orderId, OrderStatus.PAYMENT_PENDING, OrderStatus.PAID, null)) return
        val order = find(event.orderId)!!
        val campaignId = UUID.randomUUID()
        jdbc.sql(
            """
            INSERT INTO campaigns (id, order_id, partner_id, product_id, budget, cpv_price, start_date, end_date, status)
            VALUES (:id, :orderId, :partnerId, :productId, :budget, :cpvPrice, :startDate, :endDate, 'ACTIVE')
            """.trimIndent(),
        ).param("id", campaignId).param("orderId", order.id).param("partnerId", order.partnerId)
            .param("productId", order.productId).param("budget", order.budget).param("cpvPrice", order.cpvPrice)
            .param("startDate", order.startDate).param("endDate", order.endDate)
            .update()
        outbox.append(
            Topics.LIFECYCLE, "CampaignActivated", campaignId.toString(),
            CampaignActivated(campaignId, order.id, order.partnerId, order.budget, order.cpvPrice, order.startDate, order.endDate),
        )
    }

    /** 결제 실패 → 주문 취소(Saga 보상). */
    @Transactional
    fun onPaymentFailed(event: PaymentFailed) {
        transition(event.orderId, OrderStatus.PAYMENT_PENDING, OrderStatus.CANCELLED, event.reason)
    }

    private fun transition(orderId: UUID, from: OrderStatus, to: OrderStatus, reason: String?): Boolean {
        check(from.canMoveTo(to)) { "허용되지 않은 주문 상태 전이: $from → $to" }
        val updated = jdbc.sql(
            "UPDATE ad_orders SET status = :to, failure_reason = :reason, updated_at = now() WHERE id = :id AND status = :from",
        ).param("to", to.name).param("reason", reason).param("id", orderId).param("from", from.name).update()
        if (updated == 0) log.warn("주문 {} 상태 전이 {} → {} 건너뜀(이미 처리됐거나 다른 상태)", orderId, from, to)
        return updated == 1
    }
}

@Component
class PaymentResultListener(
    private val inbox: Inbox,
    private val orders: OrderService,
    private val mapper: JsonMapper,
) {
    @KafkaListener(topics = [Topics.PAYMENT_RESULT], groupId = "campaign")
    @Transactional
    fun on(message: String) {
        val event = mapper.readValue(message, EventEnvelope::class.java)
        if (!inbox.firstTime(event.eventId, "campaign")) return
        when (event.type) {
            "PaymentApproved" -> orders.onPaymentApproved(mapper.treeToValue(event.payload, PaymentApproved::class.java))
            "PaymentFailed" -> orders.onPaymentFailed(mapper.treeToValue(event.payload, PaymentFailed::class.java))
        }
    }
}

/** 과금 결과를 화면용으로 복제한다. 누적값을 받으므로 순서가 바뀌어 와도 GREATEST로 큰 값만 남긴다. */
@Component
class DeliveryChargeListener(private val inbox: Inbox, private val jdbc: JdbcClient, private val mapper: JsonMapper) {
    @KafkaListener(topics = [Topics.CHARGE], groupId = "campaign")
    @Transactional
    fun on(message: String) {
        val event = mapper.readValue(message, EventEnvelope::class.java)
        if (!inbox.firstTime(event.eventId, "campaign")) return
        val update = mapper.treeToValue(event.payload, SpentUpdate::class.java)
        val status = if (event.type == "BudgetExhausted") "EXHAUSTED" else null
        jdbc.sql(
            """
            UPDATE campaigns SET spent = GREATEST(spent, :spent),
                   status = CASE WHEN status = 'ACTIVE' THEN COALESCE(:status, status) ELSE status END, -- 환불 중인 캠페인을 되돌리지 않는다
                   updated_at = now()
            WHERE id = :id
            """.trimIndent(),
        ).param("spent", update.spentTotal).param("status", status).param("id", update.campaignId).update()
    }
}

@RestController
class OrderController(private val orders: OrderService) {

    @PostMapping("/orders")
    fun place(
        @RequestHeader("X-Partner-Id") partnerId: String,
        @RequestHeader("Idempotency-Key") key: String,
        @RequestBody request: PlaceOrderRequest,
    ): ResponseEntity<String> {
        val response = orders.place(partnerId, key, request)
        return ResponseEntity.status(if (response.replayed) 200 else response.status)
            .contentType(MediaType.APPLICATION_JSON).body(response.body)
    }

    @GetMapping("/orders/{id}")
    fun get(@RequestHeader("X-Partner-Id") partnerId: String, @PathVariable id: UUID): OrderView =
        orders.find(id)?.takeIf { it.partnerId == partnerId } ?: throw NotFound("주문 $id 이(가) 없습니다")

    @GetMapping("/campaigns")
    fun campaigns(@RequestHeader("X-Partner-Id") partnerId: String): List<CampaignView> = orders.campaigns(partnerId)
}

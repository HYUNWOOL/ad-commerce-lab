package lab.adcommerce.campaign

import lab.adcommerce.common.EventEnvelope
import lab.adcommerce.common.IdempotencyStore
import lab.adcommerce.common.Inbox
import lab.adcommerce.common.NotFound
import lab.adcommerce.common.Outbox
import lab.adcommerce.common.StoredResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

// 이벤트 페이로드(docs/02-design/03-events.md)
data class RefundRequested(val campaignId: UUID, val orderId: UUID, val partnerId: String)
data class RefundCompleted(val orderId: UUID, val paymentId: UUID, val partnerId: String, val refundAmount: Long)

class RefundNotAllowed(message: String) : RuntimeException(message)

/**
 * 부분 환불 Saga의 시작과 끝(ADR-0011). 금액은 여기서 정하지 않는다.
 * delivery가 과금을 닫는 순간의 남은 예산이 환불 금액이 된다.
 */
@Service
class RefundService(
    private val jdbc: JdbcClient,
    private val outbox: Outbox,
    private val idempotency: IdempotencyStore,
    private val mapper: JsonMapper,
    private val tx: TransactionTemplate,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun find(campaignId: UUID): CampaignView? =
        jdbc.sql("SELECT * FROM campaigns WHERE id = :id").param("id", campaignId).query(CampaignView::class.java).optional().orElse(null)

    /** 집행 중·소진된 캠페인만 환불을 시작한다. 같은 멱등키로 다시 부르면 처음 응답을 돌려준다. */
    fun request(campaignId: UUID, key: String, operator: String): StoredResponse = tx.execute {
        idempotency.execute("refund", key, campaignId.toString()) {
            val c = jdbc.sql(
                """
                UPDATE campaigns SET status = 'REFUND_REQUESTED', updated_at = now()
                WHERE id = :id AND status IN ('ACTIVE', 'EXHAUSTED')
                RETURNING id, order_id, partner_id
                """.trimIndent(),
            ).param("id", campaignId)
                .query { rs, _ -> RefundRequested(rs.getObject("id", UUID::class.java), rs.getObject("order_id", UUID::class.java), rs.getString("partner_id")) }
                .optional().orElse(null)
                ?: throw find(campaignId)?.let { RefundNotAllowed("${it.status} 상태의 캠페인은 환불을 시작할 수 없습니다") }
                    ?: NotFound("캠페인 $campaignId 이(가) 없습니다")
            outbox.append(Topics.LIFECYCLE, "RefundRequested", campaignId.toString(), c)
            log.info("캠페인 {} 환불 요청(운영자 {})", campaignId, operator)
            StoredResponse(202, mapper.writeValueAsString(find(campaignId)))
        }
    }!!

    @Transactional
    fun onRefundCompleted(event: RefundCompleted) {
        val updated = jdbc.sql(
            "UPDATE campaigns SET status = 'REFUNDED', refunded_amount = :amount, updated_at = now() WHERE order_id = :orderId AND status = 'REFUND_REQUESTED'",
        ).param("amount", event.refundAmount).param("orderId", event.orderId).update()
        if (updated == 0) log.warn("주문 {} 의 캠페인을 REFUNDED로 바꾸지 못함(이미 처리됐거나 다른 상태)", event.orderId)
    }
}

@Component
class RefundCompletedListener(private val inbox: Inbox, private val refunds: RefundService, private val mapper: JsonMapper) {
    @KafkaListener(topics = [Topics.PAYMENT_REFUND], groupId = "campaign")
    @Transactional
    fun on(message: String) {
        val event = mapper.readValue(message, EventEnvelope::class.java)
        if (event.type != "RefundCompleted" || !inbox.firstTime(event.eventId, "campaign")) return
        refunds.onRefundCompleted(mapper.treeToValue(event.payload, RefundCompleted::class.java))
    }
}

/** 운영 기능. gateway가 운영자 키에만 X-Operator-Id를 붙인다(agent 서비스가 부른다). */
@RestController
@RequestMapping("/ops/campaigns")
class OpsCampaignController(private val refunds: RefundService) {

    @GetMapping("/{id}")
    fun get(@RequestHeader("X-Operator-Id") operator: String, @PathVariable id: UUID): CampaignView =
        refunds.find(id) ?: throw NotFound("캠페인 $id 이(가) 없습니다")

    @PostMapping("/{id}/refund")
    fun refund(
        @RequestHeader("X-Operator-Id") operator: String,
        @RequestHeader("Idempotency-Key") key: String,
        @PathVariable id: UUID,
    ): ResponseEntity<String> {
        val response = refunds.request(id, key, operator)
        return ResponseEntity.status(if (response.replayed) 200 else response.status).contentType(MediaType.APPLICATION_JSON).body(response.body)
    }

    @ExceptionHandler(RefundNotAllowed::class)
    fun notAllowed(e: RefundNotAllowed): ProblemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.message)
}

package lab.adcommerce.delivery

import lab.adcommerce.common.EventEnvelope
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.ResponseEntity
import org.springframework.kafka.config.TopicBuilder
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.core.ProducerFactory
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.TimeUnit

@SpringBootApplication(scanBasePackages = ["lab.adcommerce"])
class DeliveryApplication

fun main(args: Array<String>) {
    runApplication<DeliveryApplication>(*args)
}

object Topics {
    const val IMPRESSION = "delivery.impression"
    const val CHARGE = "delivery.charge"
    const val BUDGET = "delivery.budget"
    const val LIFECYCLE = "campaign.lifecycle"
}

@Configuration
class KafkaTopics {
    @Bean fun impressionTopic() = TopicBuilder.name(Topics.IMPRESSION).partitions(3).build()
    @Bean fun chargeTopic() = TopicBuilder.name(Topics.CHARGE).partitions(3).build()
    @Bean fun budgetTopic() = TopicBuilder.name(Topics.BUDGET).partitions(3).build()
}

// 이벤트 페이로드(docs/02-design/03-events.md)
data class ImpressionReceived(val campaignId: UUID, val placement: String, val occurredAt: Instant)
data class CampaignActivated(
    val campaignId: UUID, val orderId: UUID, val partnerId: String, val budget: Long,
    val cpvPrice: Long, val startDate: LocalDate, val endDate: LocalDate,
)
data class CampaignCharged(
    val campaignId: UUID, val partnerId: String, val chargeId: UUID,
    val impressions: Long, val amount: Long, val spentTotal: Long,
)
data class BudgetExhausted(val campaignId: UUID, val partnerId: String, val spentTotal: Long)
data class RefundRequested(val campaignId: UUID, val orderId: UUID, val partnerId: String)
/** 환불 금액 확정(ADR-0011). 과금을 닫은 순간의 남은 예산이다. */
data class RefundAmountFixed(val campaignId: UUID, val orderId: UUID, val partnerId: String, val refundAmount: Long, val spentTotal: Long)

/** 광고 서버가 보내는 노출 한 건. eventId는 광고 서버가 만든다(같은 노출을 다시 보내면 같은 ID). */
data class Impression(val eventId: UUID, val campaignId: UUID, val placement: String, val occurredAt: Instant)

/**
 * 노출 수집(ADR-0007 1번). 형식만 보고 Kafka에 넣는다. DB는 건드리지 않아 수집이 과금보다 빠르다.
 * 브로커가 받았다고 확인한 뒤에 202를 준다. 그 전에 실패하면 광고 서버가 같은 eventId로 다시 보낸다.
 */
@RestController
class ImpressionController(producerFactory: ProducerFactory<String, String>, private val mapper: JsonMapper) {
    // 노출마다 트레이스 스팬이 생기지 않게 관측을 끈 템플릿을 따로 쓴다(초당 수천 건).
    private val kafka = KafkaTemplate(producerFactory)

    @PostMapping("/impressions")
    fun collect(@RequestBody impressions: List<Impression>): ResponseEntity<Map<String, Int>> {
        require(impressions.size in 1..MAX_PER_REQUEST) { "한 번에 1~${MAX_PER_REQUEST}건을 보냅니다" }
        require(impressions.all { it.placement.length in 1..64 }) { "지면(placement)은 1~64자입니다" }
        impressions.map {
            val payload = ImpressionReceived(it.campaignId, it.placement, it.occurredAt)
            val envelope = EventEnvelope(it.eventId, "ImpressionReceived", 1, it.occurredAt, it.campaignId.toString(), mapper.valueToTree(payload))
            kafka.send(Topics.IMPRESSION, it.campaignId.toString(), mapper.writeValueAsString(envelope))
        }.forEach { it.get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS) }
        return ResponseEntity.accepted().body(mapOf("accepted" to impressions.size))
    }

    companion object {
        const val MAX_PER_REQUEST = 500
        private const val SEND_TIMEOUT_SECONDS = 5L
    }
}

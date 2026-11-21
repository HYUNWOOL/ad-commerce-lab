package lab.adcommerce.settlement

import lab.adcommerce.common.EventEnvelope
import lab.adcommerce.common.Inbox
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.time.OffsetDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import java.util.UUID

@SpringBootApplication(scanBasePackages = ["lab.adcommerce"])
class SettlementApplication

fun main(args: Array<String>) {
    runApplication<SettlementApplication>(*args)
}

object Topics {
    const val PAYMENT_RESULT = "payment.result"
    const val CHARGE = "delivery.charge"
}

// 이벤트 페이로드(docs/02-design/03-events.md)
data class PaymentApproved(val orderId: UUID, val paymentId: UUID, val partnerId: String, val amount: Long, val pgTransactionId: String)
data class CampaignCharged(
    val campaignId: UUID, val partnerId: String, val chargeId: UUID,
    val impressions: Long, val amount: Long, val spentTotal: Long,
)

enum class Account { PG_RECEIVABLE, PARTNER_PREPAID, AD_REVENUE }

data class Line(val account: Account, val debit: Long = 0, val credit: Long = 0)

/** 복식부기 원장(ADR-0008). 분개 하나를 쓰고, 같은 이벤트면 아무것도 하지 않는다. */
@Component
class Ledger(private val jdbc: JdbcClient) {
    @Transactional(propagation = Propagation.MANDATORY)
    fun post(sourceEventId: UUID, partnerId: String, kind: String, occurredAt: Instant, vararg lines: Line): Boolean {
        // 앞에서 한 번 막고, DB 트리거가 커밋 때 한 번 더 막는다.
        require(lines.sumOf { it.debit } == lines.sumOf { it.credit }) { "분개의 차변 합과 대변 합이 다릅니다: ${lines.toList()}" }
        val entryId = jdbc.sql(
            """
            INSERT INTO journal_entries (source_event_id, partner_id, kind, occurred_at) VALUES (:eventId, :partnerId, :kind, :at)
            ON CONFLICT (source_event_id) DO NOTHING RETURNING id
            """.trimIndent(),
        ).param("eventId", sourceEventId).param("partnerId", partnerId).param("kind", kind)
            .param("at", OffsetDateTime.ofInstant(occurredAt, ZoneOffset.UTC))
            .query(Long::class.java).optional().orElse(null) ?: return false
        lines.forEach {
            jdbc.sql("INSERT INTO journal_lines (entry_id, account, partner_id, debit, credit) VALUES (:entry, :account, :partnerId, :debit, :credit)")
                .param("entry", entryId).param("account", it.account.name).param("partnerId", partnerId)
                .param("debit", it.debit).param("credit", it.credit).update()
        }
        return true
    }
}

@Component
class LedgerListener(private val ledger: Ledger, private val inbox: Inbox, private val mapper: JsonMapper) {

    /** 결제 승인: 받은 돈은 아직 파트너의 것(선수금)이다. */
    @KafkaListener(topics = [Topics.PAYMENT_RESULT], groupId = "settlement")
    @Transactional
    fun onPayment(message: String) {
        val event = mapper.readValue(message, EventEnvelope::class.java)
        if (event.type != "PaymentApproved" || !inbox.firstTime(event.eventId, "settlement")) return
        val p = mapper.treeToValue(event.payload, PaymentApproved::class.java)
        ledger.post(
            event.eventId, p.partnerId, "PAYMENT", event.occurredAt,
            Line(Account.PG_RECEIVABLE, debit = p.amount), Line(Account.PARTNER_PREPAID, credit = p.amount),
        )
    }

    /** 노출 과금: 선수금이 매출로 바뀐다. */
    @KafkaListener(topics = [Topics.CHARGE], groupId = "settlement")
    @Transactional
    fun onCharge(message: String) {
        val event = mapper.readValue(message, EventEnvelope::class.java)
        if (event.type != "CampaignCharged" || !inbox.firstTime(event.eventId, "settlement")) return
        val c = mapper.treeToValue(event.payload, CampaignCharged::class.java)
        ledger.post(
            event.eventId, c.partnerId, "CHARGE", event.occurredAt,
            Line(Account.PARTNER_PREPAID, debit = c.amount), Line(Account.AD_REVENUE, credit = c.amount),
        )
    }
}

/** 파트너 월 정산서. 환불은 결제 서비스에 아직 없어 0이다(ADR-0008 3번). */
data class Statement(val partnerId: String, val month: String, val paid: Long, val used: Long, val refunded: Long, val balance: Long)

@RestController
class SettlementController(private val jdbc: JdbcClient) {

    /** 파트너는 gateway가 넣어 준 X-Partner-Id로만 정한다. 다른 파트너의 정산서는 볼 수 없다. */
    @GetMapping("/settlements")
    fun statement(@RequestHeader("X-Partner-Id") partnerId: String, @RequestParam month: String): Statement {
        val ym = try {
            YearMonth.parse(month)
        } catch (e: DateTimeParseException) {
            throw IllegalArgumentException("month는 2026-12 같은 형식입니다")
        }
        val start = ym.atDay(1).atStartOfDay(SEOUL).toOffsetDateTime()
        val end = ym.plusMonths(1).atDay(1).atStartOfDay(SEOUL).toOffsetDateTime()
        return jdbc.sql(
            """
            SELECT
              COALESCE(SUM(l.credit) FILTER (WHERE e.kind = 'PAYMENT' AND e.occurred_at >= :start AND e.occurred_at < :end), 0) AS paid,
              COALESCE(SUM(l.debit)  FILTER (WHERE e.kind = 'CHARGE'  AND e.occurred_at >= :start AND e.occurred_at < :end), 0) AS used,
              COALESCE(SUM(l.credit - l.debit) FILTER (WHERE e.occurred_at < :end), 0) AS balance
            FROM journal_lines l JOIN journal_entries e ON e.id = l.entry_id
            WHERE l.account = 'PARTNER_PREPAID' AND l.partner_id = :partnerId
            """.trimIndent(),
        ).param("start", start).param("end", end).param("partnerId", partnerId)
            .query { rs, _ -> Statement(partnerId, ym.toString(), rs.getLong("paid"), rs.getLong("used"), 0, rs.getLong("balance")) }
            .single()
    }

    companion object {
        private val SEOUL: ZoneId = ZoneId.of("Asia/Seoul") // 정산 월은 한국 시간 기준
    }
}

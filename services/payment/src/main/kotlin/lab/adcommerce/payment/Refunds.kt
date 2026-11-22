package lab.adcommerce.payment

import lab.adcommerce.common.EventEnvelope
import lab.adcommerce.common.Outbox
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

// 이벤트 페이로드(docs/02-design/03-events.md)
data class RefundAmountFixed(val campaignId: UUID, val orderId: UUID, val partnerId: String, val refundAmount: Long, val spentTotal: Long)
data class RefundCompleted(val orderId: UUID, val paymentId: UUID, val partnerId: String, val refundAmount: Long)

/** 부분 환불 3단계(ADR-0011): delivery가 정한 금액만큼 PG에서 취소한다. 금액을 여기서 다시 계산하지 않는다. */
@Service
class RefundService(
    private val jdbc: JdbcClient,
    private val outbox: Outbox,
    private val pg: PgClient,
    private val tx: TransactionTemplate,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private data class Refund(val id: UUID, val paymentId: UUID, val partnerId: String, val amount: Long, val status: String, val pgTransactionId: String)

    /**
     * Inbox 대신 refunds 유니크 + PG 멱등키(환불 ID) + 조건부 완료로 멱등하다.
     * 같은 이벤트가 다시 와도, 처리 도중 죽어 다시 와도 PG 취소는 한 번이고 RefundCompleted도 한 번 나간다.
     * PG 호출은 트랜잭션 밖에서 한다. 실패하면 예외가 그대로 나가 Kafka 오류 처리기가 다시 시도한다.
     */
    fun onRefundAmountFixed(event: RefundAmountFixed) {
        val refund = tx.execute {
            jdbc.sql(
                """
                INSERT INTO refunds (id, order_id, payment_id, partner_id, amount, status)
                SELECT :id, p.order_id, p.id, p.partner_id, :amount, 'REQUESTED' FROM payments p
                WHERE p.order_id = :orderId AND p.status = 'APPROVED' AND :amount BETWEEN 0 AND p.amount
                ON CONFLICT (order_id) DO NOTHING
                """.trimIndent(),
            ).param("id", UUID.randomUUID()).param("amount", event.refundAmount).param("orderId", event.orderId).update()
            jdbc.sql(
                """
                SELECT r.id, r.payment_id, r.partner_id, r.amount, r.status, p.pg_transaction_id
                FROM refunds r JOIN payments p ON p.id = r.payment_id WHERE r.order_id = :orderId
                """.trimIndent(),
            ).param("orderId", event.orderId)
                .query { rs, _ ->
                    Refund(
                        rs.getObject("id", UUID::class.java), rs.getObject("payment_id", UUID::class.java), rs.getString("partner_id"),
                        rs.getLong("amount"), rs.getString("status"), rs.getString("pg_transaction_id"),
                    )
                }
                .optional().orElse(null)
        }
        if (refund == null) {
            log.warn("주문 {} 의 승인된 결제가 없거나 환불 금액({})이 결제 금액을 넘어 환불하지 않음", event.orderId, event.refundAmount)
            return
        }
        if (refund.status == "COMPLETED") return
        if (refund.amount > 0) pg.cancel(refund.pgTransactionId, refund.amount, refund.id)
        tx.executeWithoutResult {
            val completed = jdbc.sql("UPDATE refunds SET status = 'COMPLETED', updated_at = now() WHERE id = :id AND status = 'REQUESTED'")
                .param("id", refund.id).update()
            if (completed == 1) {
                outbox.append(
                    Topics.PAYMENT_REFUND, "RefundCompleted", event.orderId.toString(),
                    RefundCompleted(event.orderId, refund.paymentId, refund.partnerId, refund.amount),
                )
                log.info("주문 {} 환불 {}원 완료", event.orderId, refund.amount)
            }
        }
    }
}

@Component
class RefundAmountFixedListener(private val refunds: RefundService, private val mapper: JsonMapper) {
    @KafkaListener(topics = [Topics.BUDGET], groupId = "payment")
    fun on(message: String) {
        val event = mapper.readValue(message, EventEnvelope::class.java)
        if (event.type == "RefundAmountFixed") refunds.onRefundAmountFixed(mapper.treeToValue(event.payload, RefundAmountFixed::class.java))
    }
}

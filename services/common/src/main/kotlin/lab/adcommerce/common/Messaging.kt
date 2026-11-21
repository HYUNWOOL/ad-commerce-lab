package lab.adcommerce.common

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

/** 모든 이벤트의 공통 봉투(docs/02-design/03-events.md). */
data class EventEnvelope(
    val eventId: UUID,
    val type: String,
    val version: Int,
    val occurredAt: Instant,
    val aggregateId: String,
    val payload: JsonNode,
)

@Component
class Outbox(private val jdbc: JdbcClient, private val mapper: JsonMapper) {

    /** 호출한 쪽의 트랜잭션 안에서만 기록한다. 트랜잭션 밖에서 부르면 예외(상태 변경과 발행이 어긋나지 않게). */
    @Transactional(propagation = Propagation.MANDATORY)
    fun append(topic: String, type: String, aggregateId: String, payload: Any, version: Int = 1): UUID {
        val envelope = EventEnvelope(UUID.randomUUID(), type, version, Instant.now(), aggregateId, mapper.valueToTree(payload))
        jdbc.sql("INSERT INTO outbox_events (event_id, topic, message_key, payload) VALUES (:eventId, :topic, :key, :payload)")
            .param("eventId", envelope.eventId)
            .param("topic", topic)
            .param("key", aggregateId)
            .param("payload", mapper.writeValueAsString(envelope))
            .update()
        return envelope.eventId
    }
}

/** Outbox에 쌓인 이벤트를 Kafka로 옮긴다. 최소 한 번 발행이고, 중복은 소비 쪽 Inbox가 흡수한다. */
@Component
class OutboxRelay(
    private val jdbc: JdbcClient,
    private val kafka: KafkaTemplate<String, String>,
    private val tx: TransactionTemplate,
) {
    private data class Row(val id: Long, val topic: String, val key: String, val payload: String)

    @Scheduled(fixedDelayString = "\${outbox.relay.interval-ms:200}")
    fun relay() {
        while (relayBatch(BATCH) == BATCH) Unit
    }

    fun relayBatch(limit: Int): Int = tx.execute {
        // 릴레이가 여러 개 떠 있어도 같은 행을 두 번 가져가지 않는다.
        val rows = jdbc.sql(
            """
            SELECT id, topic, message_key, payload FROM outbox_events
            WHERE published_at IS NULL ORDER BY id LIMIT :limit FOR UPDATE SKIP LOCKED
            """.trimIndent(),
        ).param("limit", limit)
            .query { rs, _ -> Row(rs.getLong("id"), rs.getString("topic"), rs.getString("message_key"), rs.getString("payload")) }
            .list()
        // 순서대로 동기 발행한다. 같은 키(집계)의 이벤트 순서를 지키기 위해서다.
        rows.forEach { kafka.send(it.topic, it.key, it.payload).get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS) }
        if (rows.isNotEmpty()) {
            jdbc.sql("UPDATE outbox_events SET published_at = now() WHERE id IN (:ids)").param("ids", rows.map { it.id }).update()
        }
        rows.size
    } ?: 0

    companion object {
        private const val BATCH = 100
        private const val SEND_TIMEOUT_SECONDS = 5L
    }
}

@Component
class Inbox(private val jdbc: JdbcClient) {

    /** 처음 보는 이벤트면 true. 업무 처리와 같은 트랜잭션에서 불러야 "처리했다"와 "기록했다"가 어긋나지 않는다. */
    @Transactional(propagation = Propagation.MANDATORY)
    fun firstTime(eventId: UUID, consumer: String): Boolean =
        jdbc.sql("INSERT INTO processed_events (event_id, consumer) VALUES (:eventId, :consumer) ON CONFLICT DO NOTHING")
            .param("eventId", eventId)
            .param("consumer", consumer)
            .update() == 1
}

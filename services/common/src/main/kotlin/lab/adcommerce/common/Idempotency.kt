package lab.adcommerce.common

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest

data class StoredResponse(val status: Int, val body: String, val replayed: Boolean = false)

class IdempotencyKeyReused(key: String) : RuntimeException("Idempotency-Key '$key'가 다른 요청 본문으로 다시 쓰였습니다")

@Component
class IdempotencyStore(private val jdbc: JdbcClient) {

    /**
     * 같은 키의 첫 요청만 [handle]을 실행하고 응답을 저장한다.
     * 동시에 같은 키가 오면 첫 트랜잭션이 끝날 때까지 행 잠금으로 기다렸다가 저장된 응답을 돌려준다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    fun execute(scope: String, key: String, requestBody: String, handle: () -> StoredResponse): StoredResponse {
        val hash = sha256(requestBody)
        jdbc.sql("INSERT INTO idempotency_keys (scope, key, request_hash) VALUES (:scope, :key, :hash) ON CONFLICT DO NOTHING")
            .param("scope", scope).param("key", key).param("hash", hash).update()

        val saved = jdbc.sql(
            "SELECT request_hash, response_status, response_body FROM idempotency_keys WHERE scope = :scope AND key = :key FOR UPDATE",
        ).param("scope", scope).param("key", key)
            .query { rs, _ -> Triple(rs.getString(1), rs.getObject(2) as Int?, rs.getString(3)) }
            .single()

        if (saved.first != hash) throw IdempotencyKeyReused(key)
        if (saved.second != null) return StoredResponse(saved.second!!, saved.third, replayed = true)

        val response = handle()
        jdbc.sql("UPDATE idempotency_keys SET response_status = :status, response_body = :body WHERE scope = :scope AND key = :key")
            .param("status", response.status).param("body", response.body).param("scope", scope).param("key", key).update()
        return response
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}

package lab.adcommerce.mockpg

import io.micrometer.observation.ObservationPredicate
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.http.HttpStatus
import org.springframework.http.server.observation.ServerRequestObservationContext
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

@SpringBootApplication
class MockPgApplication {
    /** Prometheus가 5초마다 부르는 /actuator 요청은 트레이스에서 뺀다(ADR-0005). */
    @Bean
    fun dropActuatorObservations() = ObservationPredicate { _, c ->
        !(c is ServerRequestObservationContext && c.carrier?.requestURI?.startsWith("/actuator") == true)
    }
}

fun main(args: Array<String>) {
    runApplication<MockPgApplication>(*args)
}

data class ApproveRequest(val orderId: String, val amount: Long)
data class CancelRequest(val amount: Long)
data class PgPayment(
    val transactionId: String,
    val idempotencyKey: String,
    val amount: Long,
    val status: String,
    val reason: String? = null,
    val cancelledAmount: Long = 0,
)

/** 장애 주입 설정. failureRate는 확률이라 Double을 쓴다(금액 아님). */
data class Chaos(val delayMs: Long = 0, val failureRate: Double = 0.0)

/**
 * 가짜 외부 PG. 같은 Idempotency-Key는 같은 결과를 돌려준다.
 * 지연은 승인 처리 전에 걸어서, 호출하는 쪽이 타임아웃으로 끊어도 PG에서는 승인될 수 있다(결과 불명 재현).
 */
@RestController
class PgController {
    private val payments = ConcurrentHashMap<String, PgPayment>()
    private val cancels = ConcurrentHashMap<String, PgPayment>()

    @Volatile
    private var chaos = Chaos()

    @PostMapping("/v1/payments")
    fun approve(@RequestHeader("Idempotency-Key") key: String, @RequestBody request: ApproveRequest): ResponseEntity<Any> {
        Thread.sleep(chaos.delayMs)
        if (Random.nextDouble() < chaos.failureRate) {
            // 처리하지 않고 거절한다. 호출하는 쪽은 "승인 안 됨"으로 확정해도 된다.
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(mapOf("code" to "PG_BUSY"))
        }
        val payment = payments.computeIfAbsent(key) {
            if (request.amount > CARD_LIMIT) {
                PgPayment(UUID.randomUUID().toString(), key, request.amount, "DECLINED", "LIMIT_EXCEEDED")
            } else {
                PgPayment(UUID.randomUUID().toString(), key, request.amount, "APPROVED")
            }
        }
        return ResponseEntity.ok(payment)
    }

    @GetMapping("/v1/payments/{key}")
    fun find(@PathVariable key: String): ResponseEntity<PgPayment> =
        payments[key]?.let { ResponseEntity.ok(it) } ?: ResponseEntity.notFound().build()

    /** 부분 취소. 같은 Idempotency-Key로 다시 오면 처음 결과를 돌려준다(두 번 환불되지 않게, ADR-0011). */
    @PostMapping("/v1/payments/{transactionId}/cancel")
    @Synchronized
    fun cancel(
        @PathVariable transactionId: String,
        @RequestHeader("Idempotency-Key", required = false) key: String?,
        @RequestBody request: CancelRequest,
    ): ResponseEntity<Any> {
        key?.let { cancels[it] }?.let { return ResponseEntity.ok(it) }
        val entry = payments.entries.firstOrNull { it.value.transactionId == transactionId } ?: return ResponseEntity.notFound().build()
        val current = entry.value
        if (current.status != "APPROVED" || request.amount <= 0 || current.cancelledAmount + request.amount > current.amount) {
            return ResponseEntity.unprocessableEntity().body(mapOf("code" to "INVALID_CANCEL_AMOUNT"))
        }
        val updated = current.copy(cancelledAmount = current.cancelledAmount + request.amount)
        payments[entry.key] = updated
        key?.let { cancels[it] = updated }
        return ResponseEntity.ok(updated)
    }

    @PutMapping("/admin/chaos")
    fun setChaos(@RequestBody next: Chaos): Chaos {
        chaos = next
        return chaos
    }

    @GetMapping("/admin/chaos")
    fun getChaos(): Chaos = chaos

    companion object {
        /** 이 금액을 넘으면 카드 한도 초과로 거절한다(결제 실패 시나리오 S3 재현용). */
        const val CARD_LIMIT = 5_000_000L
    }
}

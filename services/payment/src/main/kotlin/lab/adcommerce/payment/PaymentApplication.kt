package lab.adcommerce.payment

import io.github.resilience4j.circuitbreaker.CallNotPermittedException
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.kafka.config.TopicBuilder
import org.springframework.stereotype.Component
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import java.net.SocketTimeoutException
import java.net.http.HttpClient
import java.net.http.HttpTimeoutException
import java.time.Duration
import java.util.UUID

@SpringBootApplication(scanBasePackages = ["lab.adcommerce"])
class PaymentApplication

fun main(args: Array<String>) {
    runApplication<PaymentApplication>(*args)
}

object Topics {
    const val ORDER = "campaign.order"
    const val PAYMENT_RESULT = "payment.result"
    const val PAYMENT_REFUND = "payment.refund"
    const val BUDGET = "delivery.budget"
}

@Configuration
class KafkaTopics {
    @Bean fun paymentResultTopic() = TopicBuilder.name(Topics.PAYMENT_RESULT).partitions(3).build()
    @Bean fun paymentRefundTopic() = TopicBuilder.name(Topics.PAYMENT_REFUND).partitions(3).build()
}

/** PG 승인 결과. 타임아웃은 "실패"가 아니라 "모름"이다(P1). */
sealed interface PgOutcome {
    data class Approved(val transactionId: String) : PgOutcome
    data class Declined(val reason: String) : PgOutcome
    /** PG가 처리하지 않았다고 확신할 수 있다(연결 실패, 503, 서킷 열림). */
    data object Unavailable : PgOutcome
    /** 응답을 받지 못했다. PG에서는 승인됐을 수 있다. */
    data object Unknown : PgOutcome
}

interface PgClient {
    fun approve(paymentId: UUID, orderId: UUID, amount: Long): PgOutcome

    /** 멱등키(결제 ID)로 PG의 결제를 조회한다. null이면 PG에 그 결제가 없다(ADR-0004). */
    fun find(paymentId: UUID): PgOutcome?

    /** 부분 취소. 같은 멱등키(환불 ID)로 다시 불러도 PG는 한 번만 취소한다. 실패하면 예외를 던진다(ADR-0011). */
    fun cancel(transactionId: String, amount: Long, idempotencyKey: UUID)
}

data class PgResponse(val transactionId: String, val status: String, val reason: String?) {
    fun toOutcome(): PgOutcome = if (status == "APPROVED") PgOutcome.Approved(transactionId) else PgOutcome.Declined(reason ?: "DECLINED")
}

@Component
class HttpPgClient(
    @Value("\${clients.pg.url}") baseUrl: String,
    @Value("\${clients.pg.read-timeout:3s}") readTimeout: Duration,
    builder: RestClient.Builder, // Boot가 주는 빌더라야 호출이 트레이스에 잡힌다
    registry: CircuitBreakerRegistry,
) : PgClient {
    // 승인과 조회는 서킷을 나눈다. 승인 타임아웃으로 열린 서킷이 그 결과를 확인할 조회까지 막지 않게 한다(S5 첫 실험에서 발견).
    private val approveBreaker = registry.circuitBreaker("pg")
    private val queryBreaker = registry.circuitBreaker("pg-query")
    private val rest = builder
        .baseUrl(baseUrl)
        .requestFactory(
            JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofMillis(500)).build())
                .apply { setReadTimeout(readTimeout) },
        )
        .build()

    override fun approve(paymentId: UUID, orderId: UUID, amount: Long): PgOutcome = call(approveBreaker) {
        // 결제 ID를 PG 멱등키로 보낸다. 같은 결제를 다시 보내도 PG에서 한 번만 승인된다.
        rest.post().uri("/v1/payments")
            .header("Idempotency-Key", paymentId.toString())
            .body(mapOf("orderId" to orderId.toString(), "amount" to amount))
            .retrieve()
            .body(PgResponse::class.java)!!
            .toOutcome()
    }!!

    // 실패(PG 장애·서킷 열림)는 예외 그대로 던진다. 리스너가 다시 받아 같은 멱등키로 재시도한다.
    override fun cancel(transactionId: String, amount: Long, idempotencyKey: UUID) = approveBreaker.executeRunnable {
        rest.post().uri("/v1/payments/{tx}/cancel", transactionId)
            .header("Idempotency-Key", idempotencyKey.toString())
            .body(mapOf("amount" to amount))
            .retrieve()
            .toBodilessEntity()
    }

    override fun find(paymentId: UUID): PgOutcome? = call(queryBreaker) {
        val response = rest.get().uri("/v1/payments/{key}", paymentId).retrieve()
            .onStatus({ it.value() == 404 }) { _, _ -> } // 없음은 오류가 아니다
            .toEntity(PgResponse::class.java)
        if (response.statusCode.value() == 404) null else response.body!!.toOutcome()
    }

    private fun call(breaker: CircuitBreaker, block: () -> PgOutcome?): PgOutcome? = try {
        breaker.executeSupplier(block)
    } catch (e: CallNotPermittedException) {
        PgOutcome.Unavailable
    } catch (e: ResourceAccessException) {
        if (generateSequence<Throwable>(e) { it.cause }.any { it is HttpTimeoutException || it is SocketTimeoutException }) PgOutcome.Unknown else PgOutcome.Unavailable
    } catch (e: RestClientException) {
        PgOutcome.Unavailable
    }
}

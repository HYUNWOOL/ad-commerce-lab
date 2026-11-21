package lab.adcommerce.payment

import io.github.resilience4j.circuitbreaker.CallNotPermittedException
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
}

@Configuration
class KafkaTopics {
    @Bean fun paymentResultTopic() = TopicBuilder.name(Topics.PAYMENT_RESULT).partitions(3).build()
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
}

data class PgResponse(val transactionId: String, val status: String, val reason: String?)

@Component
class HttpPgClient(
    @Value("\${clients.pg.url}") baseUrl: String,
    registry: CircuitBreakerRegistry,
) : PgClient {
    private val breaker = registry.circuitBreaker("pg")
    private val rest = RestClient.builder()
        .baseUrl(baseUrl)
        .requestFactory(
            JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofMillis(500)).build())
                .apply { setReadTimeout(Duration.ofSeconds(3)) },
        )
        .build()

    override fun approve(paymentId: UUID, orderId: UUID, amount: Long): PgOutcome = try {
        breaker.executeSupplier {
            // 결제 ID를 PG 멱등키로 보낸다. 같은 결제를 다시 보내도 PG에서 한 번만 승인된다.
            val response = rest.post().uri("/v1/payments")
                .header("Idempotency-Key", paymentId.toString())
                .body(mapOf("orderId" to orderId.toString(), "amount" to amount))
                .retrieve()
                .body(PgResponse::class.java)!!
            if (response.status == "APPROVED") PgOutcome.Approved(response.transactionId) else PgOutcome.Declined(response.reason ?: "DECLINED")
        }
    } catch (e: CallNotPermittedException) {
        PgOutcome.Unavailable
    } catch (e: ResourceAccessException) {
        if (generateSequence<Throwable>(e) { it.cause }.any { it is HttpTimeoutException || it is SocketTimeoutException }) PgOutcome.Unknown else PgOutcome.Unavailable
    } catch (e: RestClientException) {
        PgOutcome.Unavailable
    }
}

package lab.adcommerce.campaign

import io.github.resilience4j.circuitbreaker.CallNotPermittedException
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import lab.adcommerce.common.DependencyUnavailable
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.kafka.config.TopicBuilder
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import java.net.http.HttpClient
import java.time.Duration

@SpringBootApplication(scanBasePackages = ["lab.adcommerce"])
class CampaignApplication

fun main(args: Array<String>) {
    runApplication<CampaignApplication>(*args)
}

object Topics {
    const val ORDER = "campaign.order"
    const val LIFECYCLE = "campaign.lifecycle"
    const val PAYMENT_RESULT = "payment.result"
    const val CHARGE = "delivery.charge"
    const val PAYMENT_REFUND = "payment.refund"
}

@Configuration
class KafkaTopics {
    @Bean fun orderTopic() = TopicBuilder.name(Topics.ORDER).partitions(3).build()
    @Bean fun lifecycleTopic() = TopicBuilder.name(Topics.LIFECYCLE).partitions(3).build()
}

data class Product(
    val id: Long,
    val code: String,
    val name: String,
    val cpvPrice: Long,
    val minBudget: Long,
    val maxDays: Int,
    val active: Boolean,
)

class ProductNotFound(id: Long) : IllegalArgumentException("상품 $id 이(가) 없습니다")

interface CatalogClient {
    fun product(id: Long): Product
}

/** catalog 동기 조회. 타임아웃·서킷 브레이커를 두고, 실패하면 재시도 가능한 503으로 돌려준다(ADR-0003). */
@Component
class HttpCatalogClient(
    @Value("\${clients.catalog.url}") baseUrl: String,
    @Value("\${clients.catalog.read-timeout:1s}") readTimeout: Duration,
    builder: RestClient.Builder, // Boot가 주는 빌더라야 호출이 트레이스에 잡힌다
    registry: CircuitBreakerRegistry,
) : CatalogClient {
    private val breaker = registry.circuitBreaker("catalog")
    private val rest = builder
        .baseUrl(baseUrl)
        .requestFactory(
            JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofMillis(500)).build())
                .apply { setReadTimeout(readTimeout) },
        )
        .build()

    override fun product(id: Long): Product = try {
        breaker.executeSupplier {
            rest.get().uri("/products/{id}", id).retrieve()
                .onStatus({ it.value() == 404 }) { _, _ -> throw ProductNotFound(id) }
                .body(Product::class.java) ?: throw ProductNotFound(id)
        }
    } catch (e: CallNotPermittedException) {
        throw DependencyUnavailable("catalog")
    } catch (e: RestClientException) {
        throw DependencyUnavailable("catalog")
    }
}

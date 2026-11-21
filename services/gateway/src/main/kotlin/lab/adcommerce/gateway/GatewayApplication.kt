package lab.adcommerce.gateway

import io.micrometer.observation.ObservationPredicate
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.server.observation.ServerRequestObservationContext
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.Collections
import java.util.Enumeration

@SpringBootApplication
@EnableConfigurationProperties(ApiKeys::class)
class GatewayApplication {
    /** Prometheus가 5초마다 부르는 /actuator 요청은 트레이스에서 뺀다(ADR-0005). */
    @Bean
    fun dropActuatorObservations() = ObservationPredicate { _, c ->
        !(c is ServerRequestObservationContext && c.carrier?.requestURI?.startsWith("/actuator") == true)
    }
}

fun main(args: Array<String>) {
    runApplication<GatewayApplication>(*args)
}

/** API 키 → 파트너 ID. 회원 시스템 대신 정적 키를 쓴다(기획 범위). */
@ConfigurationProperties("gateway")
data class ApiKeys(val apiKeys: Map<String, String> = emptyMap())

/**
 * /api 아래 요청은 X-Api-Key를 확인하고, 내부 서비스가 믿을 X-Partner-Id를 붙인다.
 * 바깥에서 보낸 X-Partner-Id는 버린다(위조 방지).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class ApiKeyFilter(private val keys: ApiKeys) : OncePerRequestFilter() {

    override fun shouldNotFilter(request: HttpServletRequest) = !request.requestURI.startsWith("/api/")

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val partnerId = request.getHeader("X-Api-Key")?.let { keys.apiKeys[it] }
        if (partnerId == null) {
            response.status = 401
            response.contentType = "application/problem+json"
            response.writer.write("""{"status":401,"detail":"유효한 X-Api-Key가 필요합니다"}""")
            return
        }
        chain.doFilter(PartnerRequest(request, partnerId), response)
    }

    private class PartnerRequest(request: HttpServletRequest, private val partnerId: String) : HttpServletRequestWrapper(request) {
        override fun getHeader(name: String): String? =
            if (name.equals(HEADER, true)) partnerId else super.getHeader(name)

        override fun getHeaders(name: String): Enumeration<String> =
            if (name.equals(HEADER, true)) Collections.enumeration(listOf(partnerId)) else super.getHeaders(name)

        override fun getHeaderNames(): Enumeration<String> =
            Collections.enumeration(super.getHeaderNames().toList().filterNot { it.equals(HEADER, true) } + HEADER)
    }

    private companion object {
        const val HEADER = "X-Partner-Id"
    }
}

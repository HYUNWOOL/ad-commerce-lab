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

/** API 키 → 파트너 ID, 운영자 키 → 운영자 ID. 회원 시스템 대신 정적 키를 쓴다(기획 범위). */
@ConfigurationProperties("gateway")
data class ApiKeys(val apiKeys: Map<String, String> = emptyMap(), val operatorKeys: Map<String, String> = emptyMap())

/**
 * /api 아래 요청은 X-Api-Key를 확인하고, 내부 서비스가 믿을 신원 헤더를 하나만 붙인다.
 * /api/admin 아래는 운영자 키만 받아 X-Operator-Id를, 나머지는 파트너 키만 받아 X-Partner-Id를 붙인다(ADR-0009 7번).
 * 바깥에서 보낸 두 헤더는 모두 버린다(위조 방지).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class ApiKeyFilter(private val keys: ApiKeys) : OncePerRequestFilter() {

    override fun shouldNotFilter(request: HttpServletRequest) = !request.requestURI.startsWith("/api/")

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val key = request.getHeader("X-Api-Key")
        val admin = request.requestURI.startsWith("/api/admin/")
        val identity = key?.let { if (admin) keys.operatorKeys[it] else keys.apiKeys[it] }
        when {
            identity != null -> chain.doFilter(IdentityRequest(request, if (admin) OPERATOR else PARTNER, identity), response)
            admin && key != null && keys.apiKeys.containsKey(key) -> problem(response, 403, "운영자 키가 필요합니다")
            else -> problem(response, 401, "유효한 X-Api-Key가 필요합니다")
        }
    }

    private fun problem(response: HttpServletResponse, status: Int, detail: String) {
        response.status = status
        response.contentType = "application/problem+json"
        response.characterEncoding = "UTF-8"
        response.writer.write("""{"status":$status,"detail":"$detail"}""")
    }

    private class IdentityRequest(request: HttpServletRequest, private val header: String, private val value: String) : HttpServletRequestWrapper(request) {
        private fun dropped(name: String) = name.equals(PARTNER, true) || name.equals(OPERATOR, true)

        override fun getHeader(name: String): String? = when {
            name.equals(header, true) -> value
            dropped(name) -> null
            else -> super.getHeader(name)
        }

        override fun getHeaders(name: String): Enumeration<String> = when {
            name.equals(header, true) -> Collections.enumeration(listOf(value))
            dropped(name) -> Collections.emptyEnumeration()
            else -> super.getHeaders(name)
        }

        override fun getHeaderNames(): Enumeration<String> =
            Collections.enumeration(super.getHeaderNames().toList().filterNot(::dropped) + header)
    }

    private companion object {
        const val PARTNER = "X-Partner-Id"
        const val OPERATOR = "X-Operator-Id"
    }
}

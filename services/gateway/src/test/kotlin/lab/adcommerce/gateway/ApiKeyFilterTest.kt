package lab.adcommerce.gateway

import jakarta.servlet.http.HttpServletRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

class ApiKeyFilterTest {
    private val filter = ApiKeyFilter(ApiKeys(mapOf("partner-key" to "P-1"), mapOf("operator-key" to "ops-1")))

    /** 필터를 통과하면 뒤로 넘어간 요청을, 막히면 null과 응답 코드를 돌려준다. */
    private fun call(path: String, key: String?, vararg spoofed: Pair<String, String>): Pair<Int, HttpServletRequest?> {
        val request = MockHttpServletRequest("GET", path).apply {
            key?.let { addHeader("X-Api-Key", it) }
            spoofed.forEach { (k, v) -> addHeader(k, v) }
        }
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()
        filter.doFilter(request, response, chain)
        return response.status to chain.request as HttpServletRequest?
    }

    @Test
    fun `파트너 키는 파트너 ID만 붙이고, 바깥에서 보낸 신원 헤더는 버린다`() {
        val (status, passed) = call("/api/orders", "partner-key", "X-Partner-Id" to "P-OTHER", "X-Operator-Id" to "ops-evil")
        assertThat(status).isEqualTo(200)
        assertThat(passed!!.getHeader("X-Partner-Id")).isEqualTo("P-1")
        assertThat(passed.getHeader("X-Operator-Id")).isNull()
        assertThat(passed.headerNames.toList().count { it.equals("X-Partner-Id", true) }).isEqualTo(1)
    }

    @Test
    fun `관리 경로는 운영자 키만 통과하고 운영자 ID만 붙인다`() {
        val (status, passed) = call("/api/admin/screens/home", "operator-key", "X-Partner-Id" to "P-1")
        assertThat(status).isEqualTo(200)
        assertThat(passed!!.getHeader("X-Operator-Id")).isEqualTo("ops-1")
        assertThat(passed.getHeader("X-Partner-Id")).isNull()
    }

    @Test
    fun `파트너 키로 관리 경로를 부르면 403, 운영자 키로 파트너 경로를 부르면 401`() {
        assertThat(call("/api/admin/screens/home", "partner-key")).isEqualTo(403 to null)
        assertThat(call("/api/orders", "operator-key")).isEqualTo(401 to null)
        assertThat(call("/api/orders", null)).isEqualTo(401 to null)
    }

    @Test
    fun `api 밖의 경로(액추에이터)는 키 없이 통과한다`() {
        assertThat(call("/actuator/health", null).first).isEqualTo(200)
    }
}

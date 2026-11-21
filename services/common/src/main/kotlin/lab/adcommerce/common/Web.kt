package lab.adcommerce.common

import io.micrometer.observation.ObservationPredicate
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.http.server.observation.ServerRequestObservationContext
import org.springframework.kafka.listener.DefaultErrorHandler
import org.springframework.util.backoff.ExponentialBackOff
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.web.bind.MissingRequestHeaderException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import tools.jackson.core.JacksonException
import tools.jackson.databind.JacksonModule
import tools.jackson.module.kotlin.KotlinModule

/** 의존 서비스가 응답하지 않거나 서킷이 열렸다. 재시도 가능한 503으로 돌려준다(ADR-0003). */
class DependencyUnavailable(val dependency: String, val retryAfterSeconds: Int = 10) :
    RuntimeException("$dependency 서비스를 지금 사용할 수 없습니다")

class NotFound(message: String) : RuntimeException(message)

@RestControllerAdvice
class ProblemHandlers {
    @ExceptionHandler(IdempotencyKeyReused::class)
    fun reused(e: IdempotencyKeyReused) = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.message)

    @ExceptionHandler(IllegalArgumentException::class, MissingRequestHeaderException::class)
    fun badRequest(e: Exception) = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.message)

    @ExceptionHandler(NotFound::class)
    fun notFound(e: NotFound) = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.message)

    @ExceptionHandler(DependencyUnavailable::class)
    fun unavailable(e: DependencyUnavailable): ResponseEntity<ProblemDetail> =
        ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .header("Retry-After", e.retryAfterSeconds.toString())
            .body(ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, e.message))
}

@Configuration
@EnableScheduling
class CommonConfig {
    /** data class 이벤트·요청을 Jackson 3로 읽고 쓰기 위해 Kotlin 모듈을 등록한다. */
    @Bean
    fun kotlinJacksonModule(): JacksonModule = KotlinModule.Builder().build()

    /**
     * 잡음 관측을 버린다(ADR-0005). Outbox 릴레이는 200ms마다, Prometheus는 5초마다 /actuator를 부른다.
     * 그대로 두면 실제 요청 트레이스가 빈 트레이스에 묻힌다.
     */
    @Bean
    fun dropNoiseObservations() = ObservationPredicate { name, context ->
        name != "tasks.scheduled.execution" &&
            !(context is ServerRequestObservationContext && context.carrier?.requestURI?.startsWith("/actuator") == true)
    }

    /**
     * 처리에 실패한 Kafka 메시지를 버리지 않는다(ADR-0007). 기본 처리기는 10번 실패하면 건너뛰는데, 결제·과금 이벤트에서는 돈이 사라진다.
     * DB 장애 같은 일시적 실패는 1초부터 최대 10초 간격으로 계속 다시 처리하고, 형식이 깨진 JSON만 로그를 남기고 건너뛴다.
     */
    @Bean
    fun kafkaErrorHandler() = DefaultErrorHandler(ExponentialBackOff(1_000, 2.0).apply { maxInterval = 10_000 })
        .apply { addNotRetryableExceptions(JacksonException::class.java) }
}

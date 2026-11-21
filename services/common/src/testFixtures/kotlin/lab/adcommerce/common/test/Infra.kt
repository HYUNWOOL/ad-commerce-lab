package lab.adcommerce.common.test

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.testcontainers.kafka.KafkaContainer
import org.testcontainers.postgresql.PostgreSQLContainer

/** 서비스 통합 테스트가 실제 Postgres·Kafka에 붙도록 하는 설정. 컨테이너는 테스트 JVM 하나에서 재사용된다. */
@TestConfiguration(proxyBeanMethods = false)
class InfraContainers {
    @Bean
    @ServiceConnection
    fun postgres(): PostgreSQLContainer = PostgreSQLContainer("postgres:17.12-alpine")

    @Bean
    @ServiceConnection
    fun kafka(): KafkaContainer = KafkaContainer("apache/kafka:4.3.2")
}

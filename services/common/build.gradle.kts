plugins {
    `java-library`
    `java-test-fixtures`
}

dependencies {
    api(platform("org.springframework.boot:spring-boot-dependencies:4.1.3"))
    api("org.springframework.boot:spring-boot-starter-webmvc")
    api("org.springframework.boot:spring-boot-starter-jdbc")
    api("org.springframework.boot:spring-boot-starter-flyway")
    api("org.springframework.boot:spring-boot-starter-kafka")
    api("org.springframework.boot:spring-boot-starter-actuator")
    // 트레이스(ADR-0005). 메트릭은 Prometheus로 보므로 OTLP 메트릭 레지스트리는 뺀다.
    api("org.springframework.boot:spring-boot-starter-opentelemetry") { exclude(group = "io.micrometer", module = "micrometer-registry-otlp") }
    api("io.micrometer:micrometer-registry-prometheus") // 메트릭은 Prometheus가 긁어 간다
    api("tools.jackson.module:jackson-module-kotlin")
    api("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    // 서비스 통합 테스트가 같이 쓰는 Testcontainers 설정
    testFixturesApi(platform("org.springframework.boot:spring-boot-dependencies:4.1.3"))
    testFixturesApi("org.springframework.boot:spring-boot-starter-test")
    testFixturesApi("org.springframework.boot:spring-boot-testcontainers")
    testFixturesApi("org.testcontainers:testcontainers-postgresql")
    testFixturesApi("org.testcontainers:testcontainers-kafka")
    testFixturesApi("org.testcontainers:testcontainers-junit-jupiter")
}

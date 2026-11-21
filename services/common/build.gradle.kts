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

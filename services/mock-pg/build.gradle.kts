plugins { id("org.springframework.boot") }

// 외부 PG 대역이라 DB·Kafka 없이 단독으로 뜬다.
dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("tools.jackson.module:jackson-module-kotlin")
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") { archiveFileName.set("app.jar") }

plugins { id("org.springframework.boot") }

dependencies {
    implementation(project(":services:common"))
    implementation("org.springframework.boot:spring-boot-starter-restclient")
    implementation("io.github.resilience4j:resilience4j-spring-boot4:2.4.0")
    testImplementation(testFixtures(project(":services:common")))
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") { archiveFileName.set("app.jar") }

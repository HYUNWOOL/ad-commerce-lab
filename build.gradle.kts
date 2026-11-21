import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
    // Spring Boot 4.1.3이 관리하는 Kotlin 버전과 맞춘다(ADR-0001).
    kotlin("jvm") version "2.3.21" apply false
    kotlin("plugin.spring") version "2.3.21" apply false
    id("org.springframework.boot") version "4.1.3" apply false
}

val springBootBom = "org.springframework.boot:spring-boot-dependencies:4.1.3"

subprojects {
    group = "lab.adcommerce"

    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "org.jetbrains.kotlin.plugin.spring")

    repositories { mavenCentral() }

    extensions.configure<KotlinJvmProjectExtension> {
        jvmToolchain(25)
        compilerOptions { freeCompilerArgs.add("-Xjsr305=strict") }
    }

    dependencies {
        "implementation"(platform(springBootBom))
        "implementation"("org.jetbrains.kotlin:kotlin-reflect")
        "testImplementation"("org.springframework.boot:spring-boot-starter-test")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }

    tasks.withType<Test> {
        useJUnitPlatform()
        testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
    }
}

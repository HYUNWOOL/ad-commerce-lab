plugins { id("org.springframework.boot") }

dependencies {
    implementation(project(":services:common"))
    testImplementation(testFixtures(project(":services:common")))
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") { archiveFileName.set("app.jar") }

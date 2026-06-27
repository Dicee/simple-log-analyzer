plugins {
    kotlin("jvm")
    kotlin("plugin.serialization") version "2.2.0"
    application
}

application {
    mainClass.set("com.simpleloganalyzer.ingestion.ApplicationKt")
}

dependencies {
    implementation(project(":commons"))
    implementation(rootProject.libs.ktor.server.core)
    implementation(rootProject.libs.ktor.server.netty)
    implementation(rootProject.libs.ktor.server.content.negotiation)
    implementation(rootProject.libs.ktor.serialization.json)
    implementation(rootProject.libs.ktor.server.status.pages)
    implementation(rootProject.libs.ktor.server.call.logging)
    implementation(rootProject.libs.kotlinx.serialization.json)
    implementation(rootProject.libs.koin.ktor)
    implementation(rootProject.libs.koin.logger.slf4j)
    implementation(rootProject.libs.sqlite.jdbc)
    implementation(rootProject.libs.exposed.core)
    implementation(rootProject.libs.exposed.jdbc)
    implementation(rootProject.libs.exposed.java.time)
    implementation(rootProject.libs.logback.classic)

    testImplementation(project(":test-commons"))
    testImplementation(rootProject.libs.assertj.core)
    testImplementation(rootProject.libs.junit.jupiter)
    testImplementation(rootProject.libs.junit.platform.launcher)
    testImplementation(rootProject.libs.mockk)
}

tasks.test {
    useJUnitPlatform()
}

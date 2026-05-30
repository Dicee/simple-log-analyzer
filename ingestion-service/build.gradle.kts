plugins {
    kotlin("jvm")
    application
}

application {
    mainClass.set("com.simpleloganalyzer.ingestion.ApplicationKt")
}

dependencies {
    implementation(rootProject.libs.ktor.server.core)
    implementation(rootProject.libs.ktor.server.netty)
    implementation(rootProject.libs.ktor.server.content.negotiation)
    implementation(rootProject.libs.ktor.serialization.json)
    implementation(rootProject.libs.logback.classic)

    testImplementation(rootProject.libs.ktor.server.tests)
    testImplementation(rootProject.libs.junit.jupiter)
}

tasks.test {
    useJUnitPlatform()
}

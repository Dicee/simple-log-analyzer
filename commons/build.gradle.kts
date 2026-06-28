plugins {
    kotlin("jvm")
    kotlin("plugin.serialization") version "2.2.0"
}

dependencies {
    implementation(rootProject.libs.kotlinx.serialization.json)
    implementation(rootProject.libs.logback.classic)
}
plugins {
    kotlin("jvm")
}

dependencies {
    implementation(rootProject.libs.kotlinx.serialization.json)
    implementation(rootProject.libs.logback.classic)
}
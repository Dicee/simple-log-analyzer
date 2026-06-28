plugins {
    kotlin("jvm")
}

dependencies {
    implementation(project(":commons"))
    implementation(rootProject.libs.assertj.core)
    implementation(rootProject.libs.junit.jupiter)
    implementation(rootProject.libs.logback.classic)
    implementation(rootProject.libs.mockk)
}

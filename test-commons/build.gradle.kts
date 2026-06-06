plugins {
    kotlin("jvm")
}

dependencies {
    implementation(rootProject.libs.junit.jupiter)
    implementation(rootProject.libs.assertj.core)
    implementation(rootProject.libs.mockk)
}

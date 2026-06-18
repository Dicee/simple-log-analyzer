plugins {
    kotlin("jvm")
    kotlin("plugin.serialization") version "2.2.0"
    application
    distribution
}

application {
    mainClass.set("com.simpleloganalyzer.agent.MainKt")
    applicationName = "log-agent"
}

dependencies {
    implementation(rootProject.libs.kotlinx.coroutines.core)
    implementation(rootProject.libs.caffeine)
    implementation(rootProject.libs.kotlinx.serialization.json)
    implementation(rootProject.libs.logback.classic)
    implementation("net.peanuuutz.tomlkt:tomlkt:0.5.0")
    implementation("info.picocli:picocli:4.7.5")
    implementation(project(":commons"))

    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.12.2")

    testImplementation(rootProject.libs.junit.jupiter)
    testImplementation(rootProject.libs.assertj.core)
    testImplementation(rootProject.libs.mockk)
    testImplementation(rootProject.libs.kotlinx.coroutines.test)
    testImplementation(project(":test-commons"))
}

distributions {
    main {
        contents {
            from("scripts") {
                into("scripts")
            }
        }
    }
}

tasks.test {
    useJUnitPlatform()
}

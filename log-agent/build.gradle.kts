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
    implementation(rootProject.libs.logback.classic)
    implementation("net.peanuuutz.tomlkt:tomlkt:0.5.0")

    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.12.2")

    testImplementation(rootProject.libs.junit.jupiter)
    testImplementation(rootProject.libs.assertj.core)
    testImplementation(rootProject.libs.mockk)
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

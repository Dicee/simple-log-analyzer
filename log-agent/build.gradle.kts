plugins {
    kotlin("jvm")
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

    testImplementation(rootProject.libs.junit.jupiter)
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

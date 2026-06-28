plugins {
    kotlin("jvm") version "2.2.0" apply false
}

allprojects {
    group = "com.simpleloganalyzer"
    version = "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

subprojects {
    plugins.withId("org.jetbrains.kotlin.jvm") {
        apply(plugin = "jacoco")

        extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
            jvmToolchain(21)
            compilerOptions {
                optIn.add("kotlin.time.ExperimentalTime")
            }
        }

        tasks.withType<Test> {
            finalizedBy(tasks.named("jacocoTestReport"))
        }

        val jacocoExcludedPackages = listOf(
            "com/simpleloganalyzer/ingestion/routing/routes/**",
            "com/simpleloganalyzer/ingestion/modules/**",
            "com/simpleloganalyzer/ingestion/ApplicationKt*",
        )

        tasks.named<JacocoReport>("jacocoTestReport") {
            dependsOn(tasks.withType<Test>())
            reports {
                html.required.set(true)
                xml.required.set(false)
                csv.required.set(false)
            }
        }

        afterEvaluate {
            tasks.named<JacocoReport>("jacocoTestReport") {
                classDirectories.setFrom(
                    classDirectories.files.map { fileTree(it) { exclude(jacocoExcludedPackages) } }
                )
            }
        }
    }
}

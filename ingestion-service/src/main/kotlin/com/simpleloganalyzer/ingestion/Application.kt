package com.simpleloganalyzer.ingestion

import com.simpleloganalyzer.ingestion.modules.ingestionModule
import com.simpleloganalyzer.ingestion.routing.installErrorMappers
import com.simpleloganalyzer.ingestion.routing.logFileRoutes
import com.simpleloganalyzer.ingestion.routing.logGroupRoutes
import com.simpleloganalyzer.ingestion.routing.logStreamRoutes
import com.simpleloganalyzer.ingestion.routing.registerIngestionRoutes
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.http.content.staticResources
import io.ktor.server.netty.EngineMain
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.routing.routing
import org.koin.ktor.plugin.Koin
import org.koin.logger.slf4jLogger

fun main(args: Array<String>) {
    EngineMain.main(args)
}

fun Application.module() {
    val dbPath = environment.config.property("ingestion.dbPath").getString()

    install(Koin) {
        slf4jLogger()
        modules(ingestionModule(dbPath))
    }
    install(ContentNegotiation) {
        json()
    }
    install(CallLogging)
    install(StatusPages) {
        installErrorMappers()
    }

    registerIngestionRoutes()
    routing {
        logGroupRoutes()
        logStreamRoutes()
        logFileRoutes()
        staticResources("/", "static") {
            default("index.html")
        }
    }
}

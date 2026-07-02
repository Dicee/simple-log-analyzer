package com.simpleloganalyzer.ingestion

import com.simpleloganalyzer.ingestion.modules.ingestionModule
import com.simpleloganalyzer.ingestion.routing.installErrorMappers
import com.simpleloganalyzer.ingestion.routing.routes.logFileRoutes
import com.simpleloganalyzer.ingestion.routing.routes.logGroupRoutes
import com.simpleloganalyzer.ingestion.routing.routes.logStreamRoutes
import com.simpleloganalyzer.ingestion.routing.routes.registerIngestionRoutes
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.http.content.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.routing.*
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

    install(CORS) {
        allowHost("127.0.0.1:4200")
        allowHeader(HttpHeaders.ContentType)
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

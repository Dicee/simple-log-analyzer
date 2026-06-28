package com.simpleloganalyzer.ingestion.routing.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.routing

fun Application.registerIngestionRoutes() {
    routing {
        get("/health") {
            call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
        }

        // TODO: implement batch ingestion endpoint (R03)
        put("/v1/logs") {
            call.respond(HttpStatusCode.NotImplemented)
        }
    }
}

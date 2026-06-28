@file:UseSerializers(InstantIsoSerializer::class)

package com.simpleloganalyzer.ingestion.routing.routes

import com.simpleloganalyzer.commons.serialization.InstantIsoSerializer
import com.simpleloganalyzer.ingestion.routing.handle
import com.simpleloganalyzer.ingestion.routing.pageRequest
import com.simpleloganalyzer.ingestion.routing.pathParam
import com.simpleloganalyzer.ingestion.routing.toPage
import com.simpleloganalyzer.ingestion.service.LogFileService
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlin.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import org.koin.ktor.ext.inject

@Serializable
data class CreateLogFileRequest(
    val fileName: String,
    val firstTimestamp: Instant,
    val lastTimestamp: Instant,
)

fun Route.logFileRoutes() {
    val service by inject<LogFileService>()

    route("/v1/log-groups/{group}/streams/{stream}/files") {
        post {
            handle("createLogFile") {
                val body = call.receive<CreateLogFileRequest>()
                val created = service.create(
                    call.pathParam("group"),
                    call.pathParam("stream"),
                    body.fileName,
                    body.firstTimestamp,
                    body.lastTimestamp,
                )
                call.respond(HttpStatusCode.Created, created)
            }
        }

        get {
            handle("listLogFiles") {
                val page = call.pageRequest()
                val items = service.list(
                    call.pathParam("group"),
                    call.pathParam("stream"),
                    page.pageSize,
                    page.pageToken,
                )
                call.respond(items.toPage(page.pageSize) { it.fileName })
            }
        }
    }
}

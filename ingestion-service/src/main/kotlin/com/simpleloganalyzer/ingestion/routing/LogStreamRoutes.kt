package com.simpleloganalyzer.ingestion.routing

import com.simpleloganalyzer.ingestion.service.LogStreamService
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import org.koin.ktor.ext.inject

@Serializable
data class CreateLogStreamRequest(val streamName: String)

fun Route.logStreamRoutes() {
    val service by inject<LogStreamService>()

    route("/v1/log-groups/{group}/streams") {
        post {
            handle("createLogStream") {
                val body = call.receive<CreateLogStreamRequest>()
                val created = service.create(call.pathParam("group"), body.streamName)
                call.respond(HttpStatusCode.Created, created)
            }
        }

        get {
            handle("listLogStreams") {
                val page = call.pageRequest()
                val items = service.list(call.pathParam("group"), page.pageSize, page.pageToken)
                call.respond(items.toPage(page.pageSize) { it.streamName })
            }
        }

        delete("{stream}") {
            handle("deleteLogStream") {
                service.cascadeDelete(call.pathParam("group"), call.pathParam("stream"))
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}

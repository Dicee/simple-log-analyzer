package com.simpleloganalyzer.ingestion.routing

import com.simpleloganalyzer.ingestion.model.CompressionMode
import com.simpleloganalyzer.ingestion.model.LogFormat
import com.simpleloganalyzer.ingestion.persistence.metadata.LogGroupDao
import com.simpleloganalyzer.ingestion.service.LogGroupService
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
data class CreateLogGroupRequest(
    val name: String,
    val format: LogFormat,
    val description: String? = null,
    val compression: CompressionMode = CompressionMode.NONE,
)

fun Route.logGroupRoutes() {
    val service by inject<LogGroupService>()
    val dao by inject<LogGroupDao>()

    route("/v1/log-groups") {
        post {
            handle("createLogGroup") {
                val body = call.receive<CreateLogGroupRequest>()
                val created = service.create(body.name, body.description, body.format, body.compression)
                call.respond(HttpStatusCode.Created, created)
            }
        }

        get {
            handle("listLogGroups") {
                val page = call.pageRequest()
                val items = dao.list(page.pageSize, page.pageToken)
                call.respond(items.toPage(page.pageSize) { it.name })
            }
        }

        get("{name}") {
            handle("getLogGroup") {
                call.respond(service.get(call.pathParam("name")))
            }
        }

        delete("{name}") {
            handle("deleteLogGroup") {
                service.delete(call.pathParam("name"))
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}

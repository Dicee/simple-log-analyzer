package com.simpleloganalyzer.ingestion.routing

import com.simpleloganalyzer.ingestion.exception.BadRequestException
import com.simpleloganalyzer.ingestion.exception.ErrorCode
import com.simpleloganalyzer.ingestion.exception.InternalServiceFailureException
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import kotlin.time.measureTime

private val log = LoggerFactory.getLogger("RouteHandler")

@Serializable
data class ErrorResponse(val code: ErrorCode, val message: String)

/**
 * Reads a path parameter that the route declaration guarantees is present. The fallback exists
 * only to keep the signature non-null; it should never be hit in practice.
 */
fun RoutingCall.pathParam(name: String): String =
    parameters[name] ?: throw InternalServiceFailureException(
        "missing path parameter '$name' — route declaration is out of sync",
    )

/**
 * Common wrapper for handlers: logs the request, measures duration and converts thrown business
 * exceptions to the proper HTTP error. Unexpected throwables are wrapped as
 * [InternalServiceFailureException] so StatusPages always sees a known type.
 */
suspend fun RoutingContext.handle(operation: String, block: suspend RoutingContext.() -> Unit) {
    log.info("[{}] {} {}", operation, call.request.httpMethod.value, call.request.uri)
    try {
        val duration = measureTime { block() }
        log.info("[{}] completed in {}", operation, duration)
    } catch (e: BadRequestException) {
        log.warn("[{}] bad request: {}", operation, e.message)
        throw e
    } catch (e: InternalServiceFailureException) {
        log.error("[$operation] internal failure: $e", e)
        throw e
    } catch (e: Throwable) {
        log.error("[$operation] unexpected failure: $e", e)
        throw InternalServiceFailureException("Unexpected failure in $operation: $e", e)
    }
}

fun StatusPagesConfig.installErrorMappers() {
    exception<BadRequestException> { call: ApplicationCall, cause ->
        call.respond(HttpStatusCode.BadRequest, ErrorResponse(cause.code, cause.message.orEmpty()))
    }
    exception<InternalServiceFailureException> { call: ApplicationCall, cause ->
        call.respond(
            HttpStatusCode.InternalServerError,
            ErrorResponse(ErrorCode.INTERNAL_FAILURE, cause.message.orEmpty()),
        )
    }
}

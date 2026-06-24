package com.simpleloganalyzer.ingestion.routing

import com.simpleloganalyzer.commons.logging.log
import com.simpleloganalyzer.ingestion.exception.BadRequestException
import com.simpleloganalyzer.ingestion.exception.ErrorCode
import com.simpleloganalyzer.ingestion.exception.InternalServiceFailureException
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.statuspages.StatusPagesConfig
import io.ktor.server.request.httpMethod
import io.ktor.server.request.uri
import io.ktor.server.response.respond
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.RoutingContext
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable
import kotlin.time.measureTime
import kotlin.time.measureTimedValue

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
    val duration = try {
        measureTime { block() }
    } catch (e: BadRequestException) {
        log.warn("[{}] bad request: {}", operation, e.message)
        throw e
    } catch (e: InternalServiceFailureException) {
        log.error("[{}] internal failure", operation, e)
        throw e
    } catch (e: Throwable) {
        log.error("[{}] unexpected failure", operation, e)
        throw InternalServiceFailureException("Unexpected failure in $operation", e)
    }
    log.info("[{}] completed in {}", operation, duration)
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

package com.simpleloganalyzer.ingestion.exception

import kotlinx.serialization.Serializable

/**
 * Stable error codes returned to clients in [com.simpleloganalyzer.ingestion.routing.ErrorResponse].
 * The HTTP status is implied by the exception type that carries the code (4xx vs 5xx).
 */
@Serializable
enum class ErrorCode {
    // Generic 4xx fallback when no more specific code applies.
    BAD_REQUEST,
    INVALID_NAME,

    LOG_GROUP_ALREADY_EXISTS,
    LOG_GROUP_NOT_FOUND,
    LOG_GROUP_NOT_EMPTY,

    LOG_STREAM_ALREADY_EXISTS,
    LOG_STREAM_NOT_FOUND,

    LOG_FILE_ALREADY_EXISTS,

    // Generic 5xx fallback.
    INTERNAL_FAILURE,
}

/** Thrown for client-side errors that should map to a 4xx response. */
class BadRequestException(val code: ErrorCode, message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** Thrown for unexpected server-side failures that should map to a 5xx response. */
class InternalServiceFailureException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

fun logGroupNotFoundException(logGroup: String): BadRequestException = BadRequestException(
    ErrorCode.LOG_GROUP_NOT_FOUND, "Log group '$logGroup' does not exist",
)

fun logStreamNotFoundException(groupName: String, streamName: String): BadRequestException = BadRequestException(
    ErrorCode.LOG_STREAM_NOT_FOUND, "Log stream '$streamName' does not exist in log group '$groupName'"
)
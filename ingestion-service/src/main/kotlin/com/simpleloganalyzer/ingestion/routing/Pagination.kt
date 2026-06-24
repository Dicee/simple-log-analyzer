package com.simpleloganalyzer.ingestion.routing

import com.simpleloganalyzer.ingestion.exception.BadRequestException
import com.simpleloganalyzer.ingestion.exception.ErrorCode
import com.simpleloganalyzer.ingestion.model.Page
import io.ktor.server.routing.RoutingCall

private const val DEFAULT_PAGE_SIZE = 50
private const val MAX_PAGE_SIZE = 500

data class PageRequest(val pageSize: Int, val pageToken: String?)

fun RoutingCall.pageRequest(): PageRequest {
    val pageSize = request.queryParameters["pageSize"]?.let { raw ->
        raw.toIntOrNull() ?: throw BadRequestException(
            ErrorCode.BAD_REQUEST, "pageSize must be an integer but was $raw",
        )
    } ?: DEFAULT_PAGE_SIZE
    if (pageSize <= 0 || pageSize > MAX_PAGE_SIZE) {
        throw BadRequestException(
            ErrorCode.BAD_REQUEST, "pageSize must be between 1 and $MAX_PAGE_SIZE but was $pageSize",
        )
    }
    return PageRequest(pageSize, request.queryParameters["pageToken"])
}

/** Build a [Page] with a continuation token derived from the last item if the page is full. */
fun <T> List<T>.toPage(pageSize: Int, tokenOf: (T) -> String): Page<T> =
    Page(items = this, nextPageToken = if (size == pageSize) tokenOf(last()) else null)

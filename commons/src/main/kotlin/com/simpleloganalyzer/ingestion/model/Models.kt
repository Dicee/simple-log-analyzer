package com.simpleloganalyzer.ingestion.model

import com.simpleloganalyzer.commons.serialization.EnumSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

@Serializable(with = LogFormatSerializer::class)
enum class LogFormat(val displayName: String) {
    @SerialName("plain-text") PLAIN_TEXT("plain-text"),
    @SerialName("json") JSON("json"),
    @SerialName("logfmt") LOGFMT("logfmt"),
}
class LogFormatSerializer : EnumSerializer<LogFormat>(LogFormat::class.java)

@Serializable(with = CompressionSerializer::class)
enum class CompressionMode {
    @SerialName("none") NONE,
    @SerialName("gzip") GZIP,
}
class CompressionSerializer : EnumSerializer<CompressionMode>(CompressionMode::class.java)

@Serializable
data class LogGroup(
    val name: String,
    val description: String?,
    val creationDate: Instant,
    val format: LogFormat,
    val compression: CompressionMode,
)

@Serializable
data class LogStream(
    val logGroup: String,
    val streamName: String,
    val creationDate: Instant,
)

@Serializable
data class LogFile(
    val logGroup: String,
    val logStream: String,
    val fileName: String,
    val creationDate: Instant,
    val lastModifiedDate: Instant,
    val firstTimestamp: Instant,
    val lastTimestamp: Instant,
)

/** A page of items from a paginated listing. */
@Serializable
data class Page<T>(
    val items: List<T>,
    val nextPageToken: String? = null,
)
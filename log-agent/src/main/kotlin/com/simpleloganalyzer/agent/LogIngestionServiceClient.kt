@file:OptIn(ExperimentalTime::class)

package com.simpleloganalyzer.agent

import com.simpleloganalyzer.agent.config.CompressionMode
import com.simpleloganalyzer.commons.logging.log
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.InstantComponentSerializer
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Temporary interface just to be able to mock the ingestion service while implementing the polling agent.
 */
interface LogIngestionServiceClient {
    fun publishLogs(logGroupName: String, logStreamName: String, payload: ByteArray, compressionMode: CompressionMode)
}

@Serializable
data class RawLogEvent(
    @Serializable(with = InstantComponentSerializer::class) val timestamp: Instant,
    val message: String,
)

class InMemoryIngestionServiceClient : LogIngestionServiceClient {
    private val lock = Any()
    private val publishedEvents = mutableMapOf<String, MutableMap<String, MutableList<RawLogEvent>>>()

    override fun publishLogs(logGroupName: String, logStreamName: String, payload: ByteArray, compressionMode: CompressionMode) {
        val json = when (compressionMode) {
            CompressionMode.NONE -> payload.decodeToString()
            CompressionMode.GZIP -> GZIPInputStream(ByteArrayInputStream(payload)).use {
                it.readBytes().decodeToString()
            }
        }
        val events = Json.decodeFromString<List<RawLogEvent>>(json)
        synchronized(lock) {
            publishedEvents.getOrPut(logGroupName) { mutableMapOf() }
                .getOrPut(logStreamName) { mutableListOf() }
                .addAll(events)
        }
    }

    /** All events published for [logGroupName] / [logStreamName], in publication order. */
    fun eventsFor(logGroupName: String, logStreamName: String): List<RawLogEvent> = synchronized(lock) {
        publishedEvents[logGroupName]?.get(logStreamName).orEmpty().toList()
    }

    /**
     * Textual view of [eventsFor], one event per line formatted as `<ISO-8601 timestamp>|<message>`. Kept for future
     * tests that prefer asserting on flattened content rather than on the structured events.
     */
    fun allLogsFor(logGroupName: String, logStreamName: String): String =
        eventsFor(logGroupName, logStreamName).joinToString("") { "${it.timestamp}|${it.message}\n" }
}

class LoggingIngestionServiceClient : LogIngestionServiceClient {
    override fun publishLogs(logGroupName: String, logStreamName: String, payload: ByteArray, compressionMode: CompressionMode) {
        val json = when (compressionMode) {
            CompressionMode.NONE -> payload.decodeToString()
            CompressionMode.GZIP -> GZIPInputStream(ByteArrayInputStream(payload)).use {
                it.readBytes().decodeToString()
            }
        }
        val events = Json.decodeFromString<List<RawLogEvent>>(json)
        events.forEach { log.info("[$logGroupName/$logStreamName] ${it.timestamp}|${it.message}") }
    }
}

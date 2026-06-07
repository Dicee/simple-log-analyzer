package com.simpleloganalyzer.agent

import com.simpleloganalyzer.agent.config.DateConfig
import com.simpleloganalyzer.agent.config.FilesConfig
import com.simpleloganalyzer.agent.config.LogFormat
import com.simpleloganalyzer.commons.logging.log
import java.nio.file.Path
import java.text.ParsePosition
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalQueries.zone
import kotlin.io.path.isRegularFile
import kotlin.io.path.useDirectoryEntries
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlin.time.toKotlinInstant

private const val DEFAULT_TS_FIELD = "timestamp"

@OptIn(ExperimentalTime::class)
class LogPollerHelper(private val clock: Clock = Clock.System) {
    private val formatterCache = mutableMapOf<String, DateTimeFormatter>()

    fun findMatchingFilesInOrder(config: FilesConfig, maxFiles: Int): List<Path> {
        val files = config.rootPath.useDirectoryEntries(config.glob) { paths -> paths
                .filter { it.isRegularFile() }
                .sortedBy { it.fileName }
                .toList()
        }

        // protection against bad globs, to avoid sending unintended files to the logging service. Typically, there shouldn't be many files on disk matching
        // a certain pattern.
        if (files.size > maxFiles) {
            throw IllegalStateException("Suspiciously high number of files matching glob '${config.glob}' in ${config.rootPath} " +
                    "(found ${files.size}, max tolerated is $maxFiles). Double-check your log configuration, and if it looks correct you may override " +
                    "this safety threshold in the agent's parameters."
            )
        }

        return files
    }

    /**
     * Extracts the event timestamp from a raw event, falling back to current time in the absence of proper configuration
     * (timestamp field or date format), or in case of an exception during the extraction process.
     */
    fun extractEventTimestamp(raw: String, format: LogFormat, config: DateConfig): Instant {
        val now = clock.now()
        if (config.field == null && config.format == null) return now

        return try {
            when (format) {
                LogFormat.PLAIN_TEXT ->
                    if (config.format != null) parseInstant(raw, config.format) else now

                LogFormat.JSON, LogFormat.LOGFMT -> {
                    val field = config.field ?: DEFAULT_TS_FIELD
                    extractTimestampFromField(raw, format, field, config) ?: now
                }
            }
        } catch (e: RuntimeException) {
            log.warn("Failed to extract event timestamp, falling back to current time", e)
            now
        }
    }

    private fun extractTimestampFromField(raw: String, logFormat: LogFormat, field: String, config: DateConfig): Instant? {
        val regex = when (logFormat) {
            LogFormat.JSON -> jsonExtractFieldPattern(field, config.isEpochMillis)
            LogFormat.LOGFMT -> logfmtExtractFieldPattern(field, config.isEpochMillis)
            else -> error("Timestamp field extraction unsupported for log format $logFormat")
        }

        val matchResult = regex.findAll(raw).singleOrNull()
        return if (matchResult == null) {
            log.warn("Could not identify a single value for field $field (there may be none, or several), falling back to current time")
            null
        } else {
            val rawTimestamp = matchResult.groupValues[1].removeSurrounding("\"")
            if (config.isEpochMillis) Instant.fromEpochMilliseconds(rawTimestamp.toLong())
            else parseInstant(rawTimestamp, config.format!!)
        }
    }

    // Avoid parsing the entire JSON/logfmt
    private fun jsonExtractFieldPattern(field: String, isEpochMillis: Boolean) =
        if (isEpochMillis) Regex($$""""$$field"\s*:\s*(\d+)(?=\D|$)""")
        else Regex(""""$field"\s*:\s*"((?:[^"\\]|\\.)*)""")

    private fun logfmtExtractFieldPattern(field: String, isEpochMillis: Boolean) =
        if (isEpochMillis) Regex($$"""(?<![\\w])$$field=(\d+)(?=\D|$)""")
        else Regex("""(?<!\w)$field=("(?:[^"\\]|\\.)*"|\S+)""")

    private fun parseInstant(s: String, format: String): Instant {
        val pattern = formatterCache.getOrPut(format) { DateTimeFormatter.ofPattern(format) }
        val parsed = pattern.parse(s, ParsePosition(0))

        val zone = parsed.query(zone())
        val instant =
            if (zone != null) ZonedDateTime.from(parsed).toInstant()
            else LocalDateTime.from(parsed).atZone(ZoneId.of("UTC")).toInstant()

        return instant.toKotlinInstant()
    }
}
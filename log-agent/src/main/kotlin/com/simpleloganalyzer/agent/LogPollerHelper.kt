package com.simpleloganalyzer.agent

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.simpleloganalyzer.agent.config.DateConfig
import com.simpleloganalyzer.agent.config.FilesConfig
import com.simpleloganalyzer.agent.config.LogPollerConfig
import com.simpleloganalyzer.commons.logging.log
import com.simpleloganalyzer.commons.time.SystemTickerClock
import com.simpleloganalyzer.commons.time.TickerClock
import com.simpleloganalyzer.ingestion.model.LogFormat
import java.nio.file.Files
import java.nio.file.Path
import java.text.ParsePosition
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalQueries.zone
import java.util.concurrent.TimeUnit
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.useDirectoryEntries
import kotlin.time.Instant
import kotlin.time.toKotlinInstant

private const val DEFAULT_TS_FIELD = "timestamp"

class LogPollerHelper(
    private val clock: TickerClock = SystemTickerClock,
    private val pollerConfig: LogPollerConfig = LogPollerConfig()
) {
    private val formatterCache = mutableMapOf<String, DateTimeFormatter>()
    private val fileListCache: Cache<FilesConfig, List<Path>> = Caffeine.newBuilder()
        .expireAfterWrite(pollerConfig.fileCacheExpirySeconds, TimeUnit.SECONDS)
        .ticker { clock.readNanos() }
        .build()

    fun findMatchingFilesInOrder(config: FilesConfig): List<Path> {
        val files = fileListCache.get(config) { cfg ->
            cfg.rootPath.useDirectoryEntries(cfg.glob) { paths ->
                paths.filter { it.isRegularFile() }
                    .sortedBy { it.fileName }
                    .toList()
            }
        }

        // protection against bad globs, to avoid sending unintended files to the logging service. Typically, there shouldn't be many files on disk matching
        // a certain pattern.
        val maxFiles = pollerConfig.maxPendingFilesPerLogGroup
        if (files.size > maxFiles) {
            throw IllegalStateException("Suspiciously high number of files matching glob '${config.glob}' in ${config.rootPath} " +
                    "(found ${files.size}, max tolerated is $maxFiles). Double-check your log configuration, and if it looks correct you may override " +
                    "this safety threshold in the agent's parameters."
            )
        }

        log.debug("Listed {} for file confing {}", files.size, config)
        return files
    }

    fun invalidateListingCache(config: FilesConfig) {
        fileListCache.invalidate(config)
    }

    /**
     * Extracts the event timestamp from a raw event, or null to current time in the absence of proper configuration
     * (timestamp field or date format), or in case of an exception during the extraction process.
     */
    fun extractEventTimestamp(raw: String, format: LogFormat, config: DateConfig, isPendingEvent: Boolean = false): Instant? {
        if (config.field == null && config.format == null) return null

        return try {
            when (format) {
                LogFormat.PLAIN_TEXT ->
                    if (config.format != null) parseInstant(raw, config.format) else null

                LogFormat.JSON, LogFormat.LOGFMT -> {
                    val field = config.field ?: DEFAULT_TS_FIELD
                    extractTimestampFromField(raw, format, field, config)
                }
            }
        } catch (e: RuntimeException) {
            // if we reach here and isPendingEvent is true, it indicates that this is a multiline event. At least in normal circumstances...
            if (!isPendingEvent) log.warn("Failed to extract event timestamp, falling back to current time", e)
            null
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

    fun archive(path: Path, filesConfig: FilesConfig) {
        log.info("Archiving file with path ${path.toAbsolutePath()}")

        val archivedLogsDir = filesConfig.rootPath.resolve("archived-logs")
        Files.createDirectories(archivedLogsDir)

        Files.move(path, archivedLogsDir.resolve(path.name))
        invalidateListingCache(filesConfig)
    }
}
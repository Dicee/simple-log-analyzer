package com.simpleloganalyzer.agent

import com.simpleloganalyzer.agent.config.CompressionMode
import com.simpleloganalyzer.agent.config.LogFormat
import com.simpleloganalyzer.agent.config.LogGroupConfig
import com.simpleloganalyzer.agent.config.LogPollerConfig
import com.simpleloganalyzer.commons.logging.log
import com.simpleloganalyzer.commons.time.SystemTickerClock
import com.simpleloganalyzer.commons.time.TickerClock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import org.jetbrains.annotations.VisibleForTesting
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.lang.RuntimeException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.zip.GZIPOutputStream
import kotlin.io.path.name
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlin.time.times

// non-configurable, comes from the ingestion service
const val LOG_BATCH_SIZE = 1000

private val JITTER = 100.milliseconds

// how long to wait before restarting a log group's pipeline after a failure
private val RESTART_BACKOFF = 15.seconds
private val PUBLISH_RETRY_INITIAL_BACKOFF = 200.milliseconds
private val PUBLISH_RETRY_MAX_BACKOFF = RESTART_BACKOFF

@ExperimentalTime
internal class LogPoller(
    private val logGroupConfigs: Map<String, LogGroupConfig>,
    private val logPollerConfig: LogPollerConfig = LogPollerConfig(),
    private val ingestionServiceClient: LogIngestionServiceClient,
    private val clock: TickerClock = SystemTickerClock,
    private val helper: LogPollerHelper = LogPollerHelper(clock, logPollerConfig),
    private val cpuDispatcher: CoroutineDispatcher = Dispatchers.Default,
    // More efficient than using Dispatchers.IO since it doesn't require holding real OS threads to parallelize the IO. See
    // https://kt.academy/article/dispatcher-loom
    private val loomDispatcher: CoroutineDispatcher = Executors.newVirtualThreadPerTaskExecutor().asCoroutineDispatcher(),
) : AutoCloseable {

    fun start(scope: CoroutineScope) {
        for ((logGroupName, logGroupConfig) in logGroupConfigs) {
            val compressionMode = logGroupConfig.log.transit.compression
            scope.launch {
                // Each group is isolated: a failure is logged and the pipeline restarted, so it neither takes down
                // sibling groups nor dies silently. A restart reopens the head file from the start, which can re-send
                // already-published lines — v1 deliberately accepts these duplicates (dedup/checkpointing is deferred).
                while (isActive) {
                    try {
                        runLogGroupPipeline(logGroupName, logGroupConfig, compressionMode)
                    } catch (e: CancellationException) {
                        throw e // never swallow cancellation, or shutdown would hang
                    } catch (e: Exception) {
                        // TODO: in the first version without checkpoints, we accept duplication, and simply restart the ingestion
                        // TODO: from the head file, first line.
                        val backoff = jitter(RESTART_BACKOFF)
                        log.error("Log group '$logGroupName' pipeline failed, restarting in $backoff", e)
                        delay(backoff)
                    }
                }
            }
        }
    }

    private suspend fun runLogGroupPipeline(logGroupName: String, config: LogGroupConfig, compressionMode: CompressionMode) {
        tailLogGroup(config)
            .map { serialize(it, compressionMode) }
            .flowOn(cpuDispatcher) // serialize and optionally compress on the default pool, because it uses CPU
            .collect { payload ->
                // we could send it on another channel to decouple, but it's probably overkill
                withContext(loomDispatcher) {
                    var success = false
                    var attempt = 0

                    while (!success) {
                        try {
                            ingestionServiceClient.publishLogs(logGroupName, payload, compressionMode)
                            success = true
                        } catch (e: RuntimeException) {
                            val backoff = jitter(minOf((1 shl attempt) * PUBLISH_RETRY_INITIAL_BACKOFF, PUBLISH_RETRY_MAX_BACKOFF))

                            log.error("Failed publishing batch for $logGroupName. Retrying in $backoff...", e)
                            delay(backoff)

                            attempt += 1
                        }
                    }
                }
            }
    }

    private fun serialize(events: List<RawLogEvent>, compressionMode: CompressionMode): ByteArray {
        val json = Json.encodeToString(events).encodeToByteArray()
        return when (compressionMode) {
            CompressionMode.NONE -> json
            CompressionMode.GZIP -> ByteArrayOutputStream().also { out ->
                GZIPOutputStream(out).use { it.write(json) }
            }.toByteArray()
        }
    }

    private fun tailLogGroup(config: LogGroupConfig): Flow<List<RawLogEvent>> = flow {
        val freq = minOf(config.log.maxPutDelay / 5, 5.seconds)
        val filesConfig = config.log.files

        while (true) {
            val files = helper.findMatchingFilesInOrder(filesConfig)
            if (files.isEmpty()) {
                delay(freq)
                continue
            }

            val head = files[0]
            tailFile(head, freq, config, this)

            val archivedLogsDir = head.parent.resolve("archived-logs")
            Files.createDirectories(archivedLogsDir)

            Files.move(head, archivedLogsDir.resolve(head.name))
            helper.invalidateListingCache(filesConfig)
        }
    }.flowOn(loomDispatcher)

    private suspend fun tailFile(
        path: Path,
        freq: Duration,
        config: LogGroupConfig,
        collector: FlowCollector<List<RawLogEvent>>,
    ) {
        // TODO: handle IO exceptions (once we have checkpoints)
        path.toFile().bufferedReader().use { reader ->
            val logEventBuilder = RawLogEventBuilder(config)

            while (currentCoroutineContext().isActive) {
                val batch = nextBatch(config, freq, reader, logEventBuilder)

                if (batch.events.isNotEmpty()) collector.emit(batch.events)
                if (batch.eof) break
            }
        }
    }

    @VisibleForTesting // just for allowing more detailed tests, but we also test end-to-end
    internal suspend fun nextBatch(
        config: LogGroupConfig,
        freq: Duration,
        reader: BufferedReader,
        logEventBuilder: RawLogEventBuilder,
    ): Batch {
        var eof = false
        val events = buildList {
            var batchStartedAt: Instant? = logEventBuilder.eventStartedAt // in case there's any pending multiline event, reuse its timestamp

            while (currentCoroutineContext().isActive) {
                if (batchStartedAt != null && clock.now() - batchStartedAt >= config.log.maxPutDelay) {
                    // the batch has been open long enough, so we break, but we give some extra time to possibly incomplete multiline events - might get added
                    // to the next batch instead
                    logEventBuilder.flushIfOlderThan(config.log.maxPutDelay)?.let { add(it) }
                    break
                }

                val line = reader.readLine()
                if (line == null) {
                    // note that the helper internally caches the value for a short amount of time, so we can afford looping on this
                    val files = helper.findMatchingFilesInOrder(config.log.files)
                    if (files.size > 1) {
                        // there's a next file, so we flush the ongoing log event if any, and return to activate rotation
                        logEventBuilder.flush()?.let { add(it) }
                        eof = true
                        break
                    }
                    delay(freq) // otherwise, we wait a bit because some new data might be written in the current file
                } else {
                    if (batchStartedAt == null) batchStartedAt = clock.now()

                    val event = logEventBuilder.addLine(line)
                    if (event != null) add(event)

                    if (size == LOG_BATCH_SIZE) break
                }
            }
        }
        return Batch(events, eof)
    }

    // shuts down the backing virtual-thread executor; the caller is responsible for cancelling the polling scope first.
    // No-op when loomIo is an injected dispatcher we don't own (e.g. a test dispatcher), which isn't an ExecutorCoroutineDispatcher.
    override fun close() {
        (loomDispatcher as? ExecutorCoroutineDispatcher)?.close()
    }

    /**
     * Stateful builder which allows transparently building single or multi-line events depending on the log configuration. The builder's
     * state follows a cyclic state machine, so that the builder can be reused to create a new object after it completed the creation of
     * another. This allows carrying the state of partially consumed events when we had to flush a batch before reading the full event.
     */
    internal inner class RawLogEventBuilder(private val config: LogGroupConfig) {
        // Time we started collecting it. Important to separate from the event's timestamp in case we ingest historical logs.
        internal var eventStartedAt: Instant? = null
        private var timestamp: Instant? = null
        private val lines: MutableList<String> = mutableListOf()

        /**
         * Returns the next event if ready, or null if it's still partial
         */
        fun addLine(line: String): RawLogEvent? {
            val now = clock.now()
            val format = config.log.format
            val timestamp = helper.extractEventTimestamp(line, format, config.log.date, isPendingEvent = lines.isNotEmpty())

            // every line is an event for these formats
            if (format != LogFormat.PLAIN_TEXT) return RawLogEvent(timestamp ?: now, line)
            return if (eventStartedAt == null) {
                // if there's no timestamp on the line, we assume it's a single-line event and assign a default timestamp
                if (timestamp == null) return RawLogEvent(now, line)
                startNewEvent(timestamp, line)
                null
            } else if (timestamp != null) {
                val event = doFlush()
                startNewEvent(timestamp, line)
                event
            } else {
                lines += line
                null
            }
        }

        private fun startNewEvent(timestamp: Instant, line: String) {
            eventStartedAt = clock.now()
            this.timestamp = timestamp
            lines += line
        }

        /**
         * Flushes any pending content in a [RawLogEvent], or returns null if there is no pending content.
         */
        fun flush(): RawLogEvent? {
            return if (eventStartedAt != null) doFlush() else null
        }

        fun flushIfOlderThan(duration: Duration): RawLogEvent? {
            return if (eventStartedAt != null && clock.now() - eventStartedAt!! >= duration) doFlush() else null
        }

        private fun doFlush(): RawLogEvent {
            val event = RawLogEvent(timestamp!!, lines.joinToString("\n"))

            eventStartedAt = null
            timestamp = null
            lines.clear()

            return event
        }
    }
}

internal data class Batch(val events: List<RawLogEvent>, val eof: Boolean)
private fun jitter(duration: Duration): Duration = duration + JITTER * Math.random()

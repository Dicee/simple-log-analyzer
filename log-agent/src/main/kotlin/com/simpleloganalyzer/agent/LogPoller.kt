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
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import org.jetbrains.annotations.VisibleForTesting
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.lang.RuntimeException
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.zip.GZIPOutputStream
import kotlin.io.path.inputStream
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlin.time.times

@ExperimentalSerializationApi
@ExperimentalTime
internal class LogPoller(
    private val logGroupConfigs: Map<String, LogGroupConfig>,
    private val pollerConfig: LogPollerConfig = LogPollerConfig(),
    private val clock: TickerClock = SystemTickerClock,
    private val helper: LogPollerHelper = LogPollerHelper(clock, pollerConfig),
    private val checkpointer: Checkpointer = Checkpointer(logGroupConfigs.keys, helper),
    private val ingestionServiceClient: LogIngestionServiceClient,
    private val cpuDispatcher: CoroutineDispatcher = Dispatchers.Default,
    // More efficient than using Dispatchers.IO since it doesn't require holding real OS threads to parallelize the IO. See
    // https://kt.academy/article/dispatcher-loom
    private val loomDispatcher: CoroutineDispatcher = Executors.newVirtualThreadPerTaskExecutor().asCoroutineDispatcher(),
) : AutoCloseable {

    fun start(scope: CoroutineScope) {
        for ((logGroupName, logGroupConfig) in logGroupConfigs) {
            scope.launch {
                // Each group is isolated: a failure is logged and the pipeline restarted, so it neither takes down
                // sibling groups nor dies silently. A restart reopens the head file from the start, which can re-send
                // already-published lines — v1 deliberately accepts these duplicates (dedup/checkpointing is deferred).
                while (isActive) {
                    try {
                        runLogGroupPipeline(logGroupName, logGroupConfig)
                    } catch (e: CancellationException) {
                        throw e // never swallow cancellation, or shutdown would hang
                    } catch (e: Exception) {
                        val backoff = jitter(pollerConfig.restartBackoff)
                        log.error("Log group '$logGroupName' pipeline failed, restarting in $backoff", e)
                        delay(backoff)
                    }
                }
            }
        }
    }

    private suspend fun runLogGroupPipeline(logGroupName: String, config: LogGroupConfig) {
        val compressionMode = config.log.transit.compression
        tailLogGroup(logGroupName, config)
            .map { serialize(it, compressionMode) }
            .flowOn(cpuDispatcher) // serialize and optionally compress on the default pool, because it uses CPU
            .collect { batch ->
                // we could send it on another channel to decouple, but it's probably overkill
                withContext(loomDispatcher) {
                    publishWithRetries(logGroupName, batch, compressionMode)
                    checkpointer.commit(
                        logGroupName,
                        config = config.log.files,
                        origin = batch.origin,
                        byteOffsetInc = batch.uncompressedBytesCount,
                        eof = batch.eof,
                    )
                }
            }
    }

    private suspend fun publishWithRetries(
        logGroupName: String,
        batch: SerializedBatch,
        compressionMode: CompressionMode
    ) {
        var success = false
        var attempt = 0

        while (!success) {
            try {
                ingestionServiceClient.publishLogs(logGroupName, batch.payload, compressionMode)
                success = true
            } catch (e: RuntimeException) {
                val initialBackoff = pollerConfig.publishRetryInitialBackoff
                val maxBackoff = pollerConfig.publishRetryMaxBackoff
                val backoff = jitter(minOf((1 shl attempt) * initialBackoff, maxBackoff))

                log.error("Failed publishing batch for $logGroupName. Retrying in $backoff...", e)
                delay(backoff)

                attempt += 1
            }
        }
    }

    private fun serialize(batch: PrePublishBatch, compressionMode: CompressionMode): SerializedBatch {
        val json = Json.encodeToString(batch.events).encodeToByteArray()
        val payload = when (compressionMode) {
            CompressionMode.NONE -> json
            CompressionMode.GZIP -> ByteArrayOutputStream().also { out ->
                GZIPOutputStream(out).use { it.write(json) }
            }.toByteArray()
        }
        // ignoring Windows as a use-case, so counting 1 byte per line carriage should be true
        val uncompressedBytesCount = batch.events.asSequence().map { it.message.toByteArray().size + 1L }.sum()
        return SerializedBatch(batch.origin, uncompressedBytesCount, payload, batch.eof)
    }

    private fun tailLogGroup(logGroupName: String, config: LogGroupConfig): Flow<PrePublishBatch> = flow {
        val freq = minOf(config.log.maxPutDelay / 5, 5.seconds)
        val filesConfig = config.log.files
        // the Flow may buffer batches to publish by re-entering the loop below before we get the chance to publish the last
        // batch of the previous file, and rotation happens only after the last offset has been committed, so we keep track
        // of fully read files in memory to avoid reading them again in the interlude
        val eofEmitted = mutableSetOf<Path>()

        while (true) {
            val files = helper.findMatchingFilesInOrder(filesConfig).filter { it !in eofEmitted }
            if (files.isEmpty()) {
                delay(freq)
                continue
            }

            val checkpoint = checkpointer.getCheckpoint(logGroupName, filesConfig, files)
            tailFile(checkpoint, freq, config, this)
            eofEmitted.add(checkpoint.path)
        }
    }.flowOn(loomDispatcher)

    private suspend fun tailFile(
        checkpoint: Checkpoint,
        freq: Duration,
        config: LogGroupConfig,
        collector: FlowCollector<PrePublishBatch>,
    ) {
        val path = checkpoint.path
        val inputStream = path.inputStream()
        inputStream.skip(checkpoint.byteOffset)

        BufferedReader(InputStreamReader(inputStream)).use { reader ->
            val logEventBuilder = RawLogEventBuilder(config)
            while (currentCoroutineContext().isActive) {
                val batch = nextBatch(config, freq, path, reader, logEventBuilder)
                if (batch.events.isNotEmpty()) {
                    collector.emit(PrePublishBatch(path, batch.events, batch.eof))
                }
                if (batch.eof) break
            }
        }
    }

    @VisibleForTesting
    internal suspend fun nextBatch(
        config: LogGroupConfig,
        freq: Duration,
        path: Path,
        reader: BufferedReader,
        logEventBuilder: RawLogEventBuilder,
    ): ReadBatch {
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
                    if (hasNewerFile(files, path)) {
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

                    if (size == pollerConfig.logBatchSize) break
                }
            }
        }
        return ReadBatch(events, eof)
    }

    private fun hasNewerFile(files: List<Path>, currentPath: Path): Boolean {
        val idx = files.indexOf(currentPath)
        return idx >= 0 && idx < files.lastIndex
    }

    // shuts down the backing virtual-thread executor; the caller is responsible for cancelling the polling scope first.
    // No-op when loomIo is an injected dispatcher we don't own (e.g. a test dispatcher), which isn't an ExecutorCoroutineDispatcher.
    override fun close() {
        (loomDispatcher as? ExecutorCoroutineDispatcher)?.close()
    }

    private fun jitter(duration: Duration): Duration = duration + pollerConfig.jitter * Math.random()

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

internal data class ReadBatch(val events: List<RawLogEvent>, val eof: Boolean)
private data class PrePublishBatch(val origin: Path, val events: List<RawLogEvent>, val eof: Boolean)
private class SerializedBatch(val origin: Path, val uncompressedBytesCount: Long, val payload: ByteArray, val eof: Boolean)

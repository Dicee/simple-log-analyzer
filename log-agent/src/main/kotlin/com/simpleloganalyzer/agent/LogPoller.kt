package com.simpleloganalyzer.agent

import com.simpleloganalyzer.agent.config.CompressionMode
import com.simpleloganalyzer.agent.config.LogGroupConfig
import com.simpleloganalyzer.commons.logging.log
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
    private val ingestionServiceClient: LogIngestionServiceClient,
    private val clock: TickerClock,
    private val helper: LogPollerHelper = LogPollerHelper(clock),
    private val maxPendingFilesPerLogGroup: Int,
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
            val files = helper.findMatchingFilesInOrder(filesConfig, maxPendingFilesPerLogGroup)
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
            while (currentCoroutineContext().isActive) {
                val batch = nextBatch(config, freq, reader)
                if (batch.isEmpty()) break

                collector.emit(batch)
            }
        }
    }

    @VisibleForTesting // just for allowing more detailed tests, but we also test end-to-end
    internal suspend fun nextBatch(config: LogGroupConfig, freq: Duration, reader: BufferedReader): List<RawLogEvent> {
        return buildList {
            var batchStartedAt: Instant? = null

            while (currentCoroutineContext().isActive) {
                if (batchStartedAt != null && clock.now() - batchStartedAt >= config.log.maxPutDelay) break

                val line = reader.readLine()
                if (line == null) {
                    // note that the helper internally caches the value for a short amount of time, so we can afford looping on this
                    val files = helper.findMatchingFilesInOrder(config.log.files, maxPendingFilesPerLogGroup)
                    if (files.size > 1) break // there's a next file, so we return an empty batch for this file
                    delay(freq) // otherwise, we wait a bit because some new data might be written in the current file
                } else {
                    if (batchStartedAt == null) batchStartedAt = clock.now()

                    val timestamp = helper.extractEventTimestamp(line, config.log.format, config.log.date)
                    // TODO: handle multi-line events for plain-text
                    add(RawLogEvent(timestamp, line))

                    if (size == LOG_BATCH_SIZE) break
                }
            }
        }
    }

    // shuts down the backing virtual-thread executor; the caller is responsible for cancelling the polling scope first.
    // No-op when loomIo is an injected dispatcher we don't own (e.g. a test dispatcher), which isn't an ExecutorCoroutineDispatcher.
    override fun close() {
        (loomDispatcher as? ExecutorCoroutineDispatcher)?.close()
    }
}

private fun jitter(duration: Duration): Duration = duration + JITTER * Math.random()

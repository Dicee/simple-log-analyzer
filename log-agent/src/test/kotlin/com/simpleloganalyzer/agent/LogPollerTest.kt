package com.simpleloganalyzer.agent

import com.simpleloganalyzer.agent.config.CompressionMode
import com.simpleloganalyzer.agent.config.DEFAULT_MAX_PUT_DELAY_SECONDS
import com.simpleloganalyzer.agent.config.DateConfig
import com.simpleloganalyzer.agent.config.FilesConfig
import com.simpleloganalyzer.agent.config.LogFormat
import com.simpleloganalyzer.agent.config.LogGroupConfig
import com.simpleloganalyzer.agent.config.LogSection
import com.simpleloganalyzer.agent.config.TransitConfig
import com.simpleloganalyzer.commons.time.TickerClock
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.io.BufferedReader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@ExperimentalCoroutinesApi
@OptIn(ExperimentalTime::class)
class LogPollerTest {
    private companion object {
        const val GROUP = "my-group"
        const val MAX_PENDING_FILES = 10
        val FIXED_TIMESTAMP = Instant.fromEpochMilliseconds(0)
    }

    @TempDir private lateinit var tempDir: Path

    /**
     * Lower-level tests for [LogPoller.nextBatch], driving the batching/EOF logic directly with a real helper.
     */
    @Nested
    inner class NextBatchOnly {
        @Test
        fun testNextBatch_singleActiveFileReachesEof_flushesAccumulatedBatchOnceMaxPutDelayElapses() = runTest {
            val poller = buildPoller()
            createFile("app.log", "alpha", "beta", "gamma")

            val batch = openReader("app.log").use { reader ->
                poller.nextBatch(logGroupConfig(), freq = 1.seconds, reader)
            }

            assertThat(batch.map { it.message }).containsExactly("alpha", "beta", "gamma")
            // No newer file exists, so the batch is held open until exactly maxPutDelay of virtual time has elapsed
            assertThat(testScheduler.currentTime).isEqualTo(DEFAULT_MAX_PUT_DELAY_SECONDS * 1000L)
        }

        @Test
        fun testNextBatch_newerFileAvailableAtEof_flushesImmediatelyWithoutWaitingForMaxPutDelay() = runTest {
            val poller = buildPoller()
            createFile("app.log.1", "one", "two") // head being tailed
            createFile("app.log.2") // a newer file already exists, signalling rotation

            val batch = openReader("app.log.1").use { reader ->
                poller.nextBatch(logGroupConfig(), freq = 1.seconds, reader)
            }

            assertThat(batch.map { it.message }).containsExactly("one", "two")
            // Rotation was detected at EOF, so we flush right away rather than waiting out maxPutDelay
            assertThat(testScheduler.currentTime).isZero()
        }

        @Test
        fun testNextBatch_emptyHeadWithNewerFileAvailable_returnsEmptyBatch() = runTest {
            val poller = buildPoller()
            createFile("app.log.1") // empty head, fully consumed
            createFile("app.log.2") // newer file present

            val batch = openReader("app.log.1").use { reader ->
                poller.nextBatch(logGroupConfig(), freq = 1.seconds, reader)
            }

            assertThat(batch).isEmpty()
            assertThat(testScheduler.currentTime).isZero()
        }
    }

    /**
     * Tests for [LogPoller.start], the long-running per-log-group pipeline. These drive the whole flow end-to-end on
     * virtual time: file discovery is mocked while the actual tailing, archiving and publishing run for real against
     * files on disk and a [DummyLogIngestionServiceClient].
     */
    @Nested
    inner class EndToEndMocked {
        // Mocking only the discovery helper lets us steer rotation/error scenarios while exercising the real tailing,
        // serialization, archiving and publishing code paths. relaxUnitFun auto-stubs invalidateListingCache.
        private val helper = mockk<LogPollerHelper>(relaxUnitFun = true)
        private val client = spyk(DummyLogIngestionServiceClient())

        /**
         * Nominal flow, run for both compression modes. Starting with no file on disk, a head file with two lines appears
         * after 10s, then 5s later a third line is appended to it and a second file shows up. We assert that every line
         * is published in order, that the (now superseded) head file is rotated into archived-logs, and that the listing
         * cache is invalidated exactly once on that rotation.
         */
        @ParameterizedTest
        @EnumSource(CompressionMode::class)
        fun testStart_filesAppearOverTimeThenRotate_publishesAllLinesInOrderAndArchivesHead(compression: CompressionMode) = runTest {
            val configs = configFor(compression)
            val filesConfig = configs.getValue(GROUP).log.files
            stubLiveFileListing(filesConfig)
            stubFixedTimestamp()

            val poller = buildPoller(configs, client, helper)
            poller.start(backgroundScope)

            // No file initially; the head file (app.log.1) appears after 10 virtual seconds...
            backgroundScope.launch {
                delay(10.seconds)
                createFile("app.log.1", "line1", "line2")
            }
            // ...then 5s later a line is appended to it and a newer file (app.log.2) appears, signalling rotation.
            backgroundScope.launch {
                delay(15.seconds)
                appendLines("app.log.1", "line3")
                createFile("app.log.2", "fileb-line1")
            }

            // Comfortably past every maxPutDelay flush so the trailing file is published too.
            advanceTimeBy(200.seconds)
            runCurrent()

            assertThat(client.eventsFor(GROUP)).containsExactly(
                RawLogEvent(FIXED_TIMESTAMP, "line1"),
                RawLogEvent(FIXED_TIMESTAMP, "line2"),
                RawLogEvent(FIXED_TIMESTAMP, "line3"),
                RawLogEvent(FIXED_TIMESTAMP, "fileb-line1"),
            )

            // The head file was superseded by app.log.2, so it is moved into archived-logs and its name is preserved.
            assertThat(tempDir.resolve("app.log.1")).doesNotExist()
            assertThat(tempDir.resolve("archived-logs").resolve("app.log.1")).exists()
            // The trailing file has no successor, so it keeps being tailed and is never rotated.
            assertThat(tempDir.resolve("app.log.2")).exists()

            // Exactly one rotation happened, so the cache is invalidated exactly once, for this group's files config.
            verify(exactly = 1) { helper.invalidateListingCache(filesConfig) }
        }

        /**
         * The publish client rejects a batch twice before succeeding. The retry loop must resend the *same* batch, so
         * the data lands exactly once with no duplication, after exactly three publish attempts.
         */
        @Test
        fun testStart_publishFailsTwiceThenSucceeds_resendsSameBatchWithoutDuplicates() = runTest {
            // First two attempts blow up; the third (and any later) call records the batch for real. Only the
            // serialized payload varies, so the group name and compression mode are matched exactly.
            every { client.publishLogs(GROUP, any(), CompressionMode.NONE) }
                .throws(RuntimeException("publish failed #1"))
                .andThenThrows(RuntimeException("publish failed #2"))
                .andThenAnswer { callOriginal() }

            val configs = configFor(CompressionMode.NONE)
            val filesConfig = configs.getValue(GROUP).log.files
            stubLiveFileListing(filesConfig)
            stubFixedTimestamp()
            createFile("app.log.1", "line1", "line2", "line3")

            val poller = buildPoller(configs, client, helper)
            poller.start(backgroundScope)

            advanceTimeBy(120.seconds)
            runCurrent()

            // The single batch was retried until it succeeded: two failures + one success, and the lines appear once.
            verify(exactly = 3) { client.publishLogs(GROUP, any(), CompressionMode.NONE) }
            assertThat(client.eventsFor(GROUP)).containsExactly(
                RawLogEvent(FIXED_TIMESTAMP, "line1"),
                RawLogEvent(FIXED_TIMESTAMP, "line2"),
                RawLogEvent(FIXED_TIMESTAMP, "line3"),
            )
        }

        /**
         * File discovery throws once, after the head file has already had a batch published. Without checkpoints, v1
         * recovers by restarting the pipeline from the head file's first line, which re-sends the already-published
         * lines. We assert that the restart happens and that it produces exactly that documented duplication.
         */
        @Test
        fun testStart_fileListingThrowsAfterFirstBatch_restartsAndReprocessesHeadFileWithDuplication() = runTest {
            val configs = configFor(CompressionMode.NONE)
            val filesConfig = configs.getValue(GROUP).log.files
            stubFixedTimestamp()

            val headFile = createFile("app.log.1", "line1", "line2")
            var hasThrown = false
            // Always report the same single head file, except for one transient failure on the first listing that
            // happens after the first batch has been published (i.e. after the head file was partially consumed).
            every { helper.findMatchingFilesInOrder(filesConfig, MAX_PENDING_FILES) } answers {
                if (!hasThrown && client.eventsFor(GROUP).isNotEmpty()) {
                    hasThrown = true
                    throw RuntimeException("transient listing failure")
                }
                listOf(headFile)
            }

            val poller = buildPoller(configs, client, helper)
            poller.start(backgroundScope)

            // Enough for: first flush -> failure -> restart backoff -> second flush.
            advanceTimeBy(200.seconds)
            runCurrent()

            assertThat(hasThrown).isTrue()
            // The head file is reopened from the start after the restart, re-sending line1/line2 (accepted v1 duplication).
            assertThat(client.eventsFor(GROUP)).containsExactly(
                RawLogEvent(FIXED_TIMESTAMP, "line1"),
                RawLogEvent(FIXED_TIMESTAMP, "line2"),
                RawLogEvent(FIXED_TIMESTAMP, "line1"),
                RawLogEvent(FIXED_TIMESTAMP, "line2"),
            )
        }

        private fun configFor(compression: CompressionMode): Map<String, LogGroupConfig> =
            mapOf(GROUP to logGroupConfig(compression))

        /** Makes the discovery helper reflect the live top-level contents of [tempDir], mirroring real rotation. */
        private fun stubLiveFileListing(filesConfig: FilesConfig) {
            every { helper.findMatchingFilesInOrder(filesConfig, MAX_PENDING_FILES) } answers { listMatchingFiles() }
        }

        // Only the raw line varies; the plain-text format and (empty) date config are fixed by the log group config.
        private fun stubFixedTimestamp() {
            every { helper.extractEventTimestamp(any(), LogFormat.PLAIN_TEXT, DateConfig()) } returns FIXED_TIMESTAMP
        }

        private fun listMatchingFiles(): List<Path> =
            Files.newDirectoryStream(tempDir, "app.log*").use { stream ->
                stream.filter { Files.isRegularFile(it) }.sortedBy { it.fileName.toString() }
            }
    }

    /**
     * A [TickerClock] backed by the test scheduler's virtual clock, so wall-clock time and `delay(...)` advance
     * together — exactly the production relationship that the maxPutDelay batching logic relies on.
     */
    private fun TestScope.virtualClock() = object : TickerClock {
        override fun now() = Instant.fromEpochMilliseconds(testScheduler.currentTime)
        override fun readNanos() = testScheduler.currentTime * 1_000_000
    }

    /**
     * Builds a poller wired entirely onto the test scheduler. [helper] defaults to a real [LogPollerHelper] (used by
     * the lower-level [NextBatchOnly] tests, which exercise discovery/parsing for real); the [EndToEndMocked] tests inject a mock.
     */
    private fun TestScope.buildPoller(
        configs: Map<String, LogGroupConfig> = emptyMap(),
        client: LogIngestionServiceClient = DummyLogIngestionServiceClient(),
        helper: LogPollerHelper = LogPollerHelper(virtualClock()),
    ): LogPoller {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return LogPoller(
            logGroupConfigs = configs,
            ingestionServiceClient = client,
            clock = virtualClock(),
            helper = helper,
            maxPendingFilesPerLogGroup = MAX_PENDING_FILES,
            cpuDispatcher = dispatcher,
            loomDispatcher = dispatcher,
        )
    }

    private fun logGroupConfig(compression: CompressionMode = CompressionMode.GZIP) = LogGroupConfig(
        LogSection(
            files = FilesConfig(root = tempDir.toString(), glob = "app.log*"),
            transit = TransitConfig(compression),
        ),
    )

    private fun createFile(name: String, vararg lines: String): Path {
        val content = if (lines.isEmpty()) "" else lines.joinToString("\n", postfix = "\n")
        return Files.writeString(tempDir.resolve(name), content)
    }

    private fun appendLines(name: String, vararg lines: String) {
        Files.writeString(tempDir.resolve(name), lines.joinToString("\n", postfix = "\n"), StandardOpenOption.APPEND)
    }

    private fun openReader(name: String): BufferedReader = tempDir.resolve(name).toFile().bufferedReader()
}

package com.simpleloganalyzer.agent

import com.simpleloganalyzer.agent.config.FilesConfig
import com.simpleloganalyzer.agent.config.LogGroupConfig
import com.simpleloganalyzer.agent.config.LogSection
import com.simpleloganalyzer.commons.time.TickerClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.BufferedReader
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@ExperimentalCoroutinesApi
@OptIn(ExperimentalTime::class)
class LogPollerTest {
    @TempDir private lateinit var tempDir: Path

    @Test
    fun testNextBatch_singleActiveFileReachesEof_flushesAccumulatedBatchOnceMaxPutDelayElapses() = runTest {
        val poller = newPoller()
        createFile("app.log", "alpha", "beta", "gamma")

        val batch = openReader("app.log").use { reader ->
            poller.nextBatch(logGroupConfig(), freq = 1.seconds, reader)
        }

        assertThat(batch.map { it.message }).containsExactly("alpha", "beta", "gamma")
        // No newer file exists, so the batch is held open until exactly maxPutDelay (60s default) of virtual time has elapsed
        assertThat(testScheduler.currentTime).isEqualTo(60_000)
    }

    @Test
    fun testNextBatch_newerFileAvailableAtEof_flushesImmediatelyWithoutWaitingForMaxPutDelay() = runTest {
        val poller = newPoller()
        createFile("app.log.1", "one", "two") // head being tailed
        createFile("app.log.2")               // a newer file already exists, signalling rotation

        val batch = openReader("app.log.1").use { reader ->
            poller.nextBatch(logGroupConfig(), freq = 1.seconds, reader)
        }

        assertThat(batch.map { it.message }).containsExactly("one", "two")
        // Rotation was detected at EOF, so we flush right away rather than waiting out maxPutDelay
        assertThat(testScheduler.currentTime).isZero()
    }

    @Test
    fun testNextBatch_emptyHeadWithNewerFileAvailable_returnsEmptyBatch() = runTest {
        val poller = newPoller()
        createFile("app.log.1")          // empty head, fully consumed
        createFile("app.log.2")          // newer file present

        val batch = openReader("app.log.1").use { reader ->
            poller.nextBatch(logGroupConfig(), freq = 1.seconds, reader)
        }

        assertThat(batch).isEmpty()
        assertThat(testScheduler.currentTime).isZero()
    }

    /**
     * A [TickerClock] backed by the test scheduler's virtual clock, so wall-clock time and `delay(...)` advance
     * together — exactly the production relationship that the maxPutDelay batching logic relies on.
     */
    private fun TestScope.virtualClock() = object : TickerClock {
        override fun now() = Instant.fromEpochMilliseconds(testScheduler.currentTime)
        override fun readNanos() = testScheduler.currentTime * 1_000_000
    }

    private fun TestScope.newPoller(): LogPoller {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return LogPoller(
            logGroupConfigs = emptyMap(), // unused by nextBatch
            ingestionServiceClient = DummyLogIngestionServiceClient(),
            clock = virtualClock(),
            maxPendingFilesPerLogGroup = 10,
            cpuDispatcher = dispatcher,
            loomDispatcher = dispatcher,
        )
    }

    private fun logGroupConfig() = LogGroupConfig(LogSection(files = FilesConfig(root = tempDir.toString(), glob = "app.log*")))

    private fun createFile(name: String, vararg lines: String): Path {
        val content = if (lines.isEmpty()) "" else lines.joinToString("\n", postfix = "\n")
        return Files.writeString(tempDir.resolve(name), content)
    }

    private fun openReader(name: String): BufferedReader = tempDir.resolve(name).toFile().bufferedReader()
}

package com.simpleloganalyzer.agent

import com.simpleloganalyzer.agent.config.DateConfig
import com.simpleloganalyzer.agent.config.FilesConfig
import com.simpleloganalyzer.agent.config.LogGroupConfig
import com.simpleloganalyzer.agent.config.LogPollerConfig
import com.simpleloganalyzer.agent.config.LogSection
import com.simpleloganalyzer.agent.config.LogStreamResolver
import com.simpleloganalyzer.agent.config.TransitConfig
import com.simpleloganalyzer.commons.time.SystemTickerClock
import com.simpleloganalyzer.ingestion.model.CompressionMode
import com.simpleloganalyzer.ingestion.model.LogFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.ExperimentalSerializationApi
import org.assertj.core.api.SoftAssertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Real-time end-to-end test for [LogPoller]. Unlike [LogPollerTest], which drives the poller on a virtual clock with
 * mocked file discovery, this test runs the production [LogPollerHelper] against a real temp directory and the real
 * [SystemTickerClock]. The only piece left mocked is the ingestion client — kept as the in-memory dummy so we can
 * assert on the textual output it records.
 *
 * Each log group's input is fed line-by-line into the watched directory at random 10–250 ms intervals to mimic an
 * application writing to its log file, and rotation is triggered the way it would happen in production: by creating
 * a successor file in the same directory. The three groups run in parallel to exercise concurrent pipelines.
 */
@ExperimentalSerializationApi
class LogPollerEndToEndTest {
    private companion object {
        const val TS_FORMAT = "yyyy-MM-dd HH:mm:ss"
        const val STREAM_NAME = "e2e-test-stream"

        // A short maxPutDelay keeps the trailing file's final batch from sitting in the builder for the production
        // default (60s), so the test completes well within its 30s budget without sacrificing realism of the path.
        const val MAX_PUT_DELAY_SECONDS = 1
    }

    private data class LogGroup(val format: LogFormat, val date: DateConfig) {
        val name = "${format.displayName}-group"
        val resourceDir = format.displayName
    }

    private val groups = listOf(
        LogGroup(LogFormat.JSON, DateConfig(format = TS_FORMAT)),
        LogGroup(LogFormat.LOGFMT, DateConfig(format = TS_FORMAT)),
        // Plain-text intentionally has multi-line events (stacktraces) to exercise the multi-line
        // event-builder code path, which JSON and logfmt skip since every line is its own event.
        LogGroup(LogFormat.PLAIN_TEXT, DateConfig(format = TS_FORMAT)),
    )

    @TempDir private lateinit var tempDir: Path

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    fun testStart_threeGroupsInParallel_allFormats(): Unit = runBlocking {
        val client = InMemoryIngestionServiceClient()

        val groupDirs = groups.associateWith { g ->
            val dir = Files.createDirectory(tempDir.resolve(g.name))
            // Pre-create an empty head file so the poller's listing cache doesn't latch onto an "empty" result and
            // make us wait out its 10s TTL before discovering the file we are about to feed lines into.
            Files.createFile(dir.resolve("app.log.1"))
            dir
        }

        val configs = groups.associate { g ->
            g.name to LogGroupConfig(
                LogSection(
                    files = FilesConfig(root = groupDirs.getValue(g).toString(), glob = "app.log*"),
                    format = g.format,
                    date = g.date,
                    transit = TransitConfig(CompressionMode.NONE),
                    maxPutDelaySeconds = MAX_PUT_DELAY_SECONDS,
                )
            )
        }

        // Use a dedicated scope so the indefinitely-running per-group coroutines can be cancelled cleanly at the end
        // regardless of how the assertions go. SupervisorJob keeps one group's failure from killing the others.
        val pollerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val poller = LogPoller(
            logGroupConfigs = configs,
            pollerConfig = LogPollerConfig(
                fileCacheExpirySeconds = 1,
                logStreamResolver = LogStreamResolver.defaultChainResolver(STREAM_NAME),
            ),
            ingestionServiceClient = client,
            clock = SystemTickerClock,
        )

        try {
            poller.start(pollerScope)

            // feed all three groups concurrently — each group ships its own pipeline
            coroutineScope {
                for (g in groups) {
                    launch(Dispatchers.IO) {
                        val dir = groupDirs.getValue(g)
                        feedFileWithRandomDelays(dir.resolve("app.log.1"), inputLines(g, "input-1.log"))
                        Files.createFile(dir.resolve("app.log.2"))
                        feedFileWithRandomDelays(dir.resolve("app.log.2"), inputLines(g, "input-2.log"))
                    }
                }
            }

            val expected = groups.associate { g -> g.name to expectedOutput(g) }

            // 10s leaves comfortable headroom under the 15s test timeout while still allowing us to show whatever has been consumed so far,
            // which is more useful than just a timeout failure
            val finished = withTimeoutOrNull(10.seconds) {
                while (groups.any { client.allLogsFor(it.name, STREAM_NAME) != expected.getValue(it.name) }) {
                    delay(100.milliseconds)
                }
                true
            }

            val softly = SoftAssertions()
            softly.assertThat(finished).describedAs("all groups reached their expected transcripts before the wait timeout").isTrue()

            for (g in groups) {
                softly.assertThat(client.allLogsFor(g.name, STREAM_NAME))
                    .describedAs("transcript for %s", g.name)
                    .isEqualTo(expected.getValue(g.name))
            }
            softly.assertAll()
        } finally {
            pollerScope.coroutineContext[Job]!!.cancelAndJoin()
            poller.close()
        }
    }

    private suspend fun feedFileWithRandomDelays(path: Path, lines: List<String>) {
        for (line in lines) {
            Files.writeString(path, line + "\n", StandardOpenOption.APPEND)
            delay(Random.nextLong(10, 251).milliseconds)
        }
    }

    private fun inputLines(group: LogGroup, name: String): List<String> =
        resourceStream("/end-to-end/${group.resourceDir}/$name").bufferedReader().use { it.readLines() }

    // Normalize CRLF→LF so the test is insensitive to how the fixture is checked out on Windows.
    private fun expectedOutput(group: LogGroup): String =
        resourceStream("/end-to-end/${group.resourceDir}/expected.txt")
            .bufferedReader().use { it.readText() }
            .replace("\r\n", "\n")

    private fun resourceStream(path: String) =
        this::class.java.getResourceAsStream(path) ?: error("Test resource not found: $path")
}

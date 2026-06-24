
package com.simpleloganalyzer.agent

import com.simpleloganalyzer.agent.config.DateConfig
import com.simpleloganalyzer.agent.config.DEFAULT_FILE_CACHE_EXPIRY_SECONDS
import com.simpleloganalyzer.agent.config.FilesConfig
import com.simpleloganalyzer.agent.config.LogPollerConfig
import com.simpleloganalyzer.ingestion.model.LogFormat
import com.simpleloganalyzer.testcommons.assertions.MoreAssertions
import com.simpleloganalyzer.testcommons.time.FakeTickerClock
import io.mockk.MockKAnnotations
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.MethodSource
import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Stream
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class LogPollerHelperTest {
    @TempDir private lateinit var tempDir: Path

    private lateinit var clock: FakeTickerClock

    private val now = Instant.fromEpochMilliseconds(1_700_000_000_000L)
    private lateinit var helper: LogPollerHelper

    @BeforeEach
    fun setUp() {
        MockKAnnotations.init(this)

        clock = FakeTickerClock(initialInstant = now)
        helper = LogPollerHelper(clock)
    }

    @Test
    fun testFindMatchingFilesInOrder_noMatchingFiles_returnsEmpty() {
        createFile("app.txt")

        val result = helper.findMatchingFilesInOrder(config("*.log"))
        assertThat(result).isEmpty()
    }

    @Test
    fun testFindMatchingFilesInOrder_matchingFiles_returnsSortedByFileName() {
        createFile("c.log")
        createFile("a.log")
        createFile("b.log")

        val result = helper.findMatchingFilesInOrder(config("*.log"))
        assertThatFileNames(result).containsExactly("a.log", "b.log", "c.log")
    }

    @Test
    fun testFindMatchingFilesInOrder_nonMatchingFilesExcluded() {
        createFile("app.log")
        createFile("app.txt")
        createFile("other.log.gz")

        val result = helper.findMatchingFilesInOrder(config("*.log"))
        assertThatFileNames(result).containsExactly("app.log")
    }

    @Test
    fun testFindMatchingFilesInOrder_wildcardSuffix_matchesRotatedLogs() {
        createFile("application.log")
        createFile("application.log.1")
        createFile("application.log.2")
        createFile("other.log")

        val result = helper.findMatchingFilesInOrder(config("application.log*"))
        assertThatFileNames(result).containsExactly("application.log", "application.log.1", "application.log.2")
    }

    @Test
    fun testFindMatchingFilesInOrder_directoriesExcluded() {
        createFile("app.log")
        createDir("subdir.log")

        val result = helper.findMatchingFilesInOrder(config("*.log"))
        assertThatFileNames(result).containsExactly("app.log")
    }

    @Test
    fun testFindMatchingFilesInOrder_exactlyMaxFiles_returnsFiles() {
        createFile("a.log")
        createFile("b.log")
        createFile("c.log")

        helper = LogPollerHelper(clock, LogPollerConfig(maxPendingFilesPerLogGroup = 3))
        val result = helper.findMatchingFilesInOrder(config("*.log"))
        assertThatFileNames(result).containsExactly("a.log", "b.log", "c.log")
    }

    @Test
    fun testFindMatchingFilesInOrder_caching_returnsStaleThenRefreshesAfterExpiry() {
        createFile("a.log")

        val first = helper.findMatchingFilesInOrder(config("*.log"))
        assertThatFileNames(first).containsExactly("a.log")

        // New file added — cache hit should still return the stale result
        createFile("b.log")
        val second = helper.findMatchingFilesInOrder(config("*.log"))
        assertThatFileNames(second).containsExactly("a.log")

        // Advance past the 10-second expiry — cache miss should return the fresh result
        clock.advanceBy(DEFAULT_FILE_CACHE_EXPIRY_SECONDS.seconds).advanceBy(1.seconds)
        val third = helper.findMatchingFilesInOrder(config("*.log"))
        assertThatFileNames(third).containsExactly("a.log", "b.log")
    }

    @Test
    fun testInvalidateListingCache_clearsEntry_nextCallSeesFreshStateWithoutWaitingForExpiry() {
        createFile("a.log")

        val first = helper.findMatchingFilesInOrder(config("*.log"))
        assertThatFileNames(first).containsExactly("a.log")

        // New file added — a cache hit still returns the stale result, proving the listing was cached
        createFile("b.log")
        val cached = helper.findMatchingFilesInOrder(config("*.log"))
        assertThatFileNames(cached).containsExactly("a.log")

        // Invalidate and call again *without* advancing the clock past the 10-second expiry — the fresh result is returned
        helper.invalidateListingCache(config("*.log"))
        val afterInvalidation = helper.findMatchingFilesInOrder(config("*.log"))
        assertThatFileNames(afterInvalidation).containsExactly("a.log", "b.log")
    }

    @Test
    fun testFindMatchingFilesInOrder_exceedsMaxFiles_throwsIllegalStateException() {
        createFile("a.log")
        createFile("b.log")
        createFile("c.log")

        helper = LogPollerHelper(clock, LogPollerConfig(maxPendingFilesPerLogGroup = 2))
        MoreAssertions.assertThatThrownBy { helper.findMatchingFilesInOrder(config("*.log")) }
            .isLike(IllegalStateException(
            "Suspiciously high number of files matching glob '*.log' in $tempDir " +
                "(found 3, max tolerated is 2). Double-check your log configuration, and if it looks correct you may override " +
                "this safety threshold in the agent's parameters."
        ))
    }

    @ParameterizedTest
    @EnumSource(LogFormat::class)
    fun testExtractEventTimestamp_allFormats_nothingConfigured_fallsBackToNow(logFormat: LogFormat) {
        val result = helper.extractEventTimestamp("hello world", logFormat, DateConfig())
        assertThat(result).isNull()
    }

    @Test
    fun testExtractEventTimestamp_plainText_noDateFormat_returnsNow() {
        val result = helper.extractEventTimestamp("some log line", LogFormat.PLAIN_TEXT, DateConfig())
        assertThat(result).isNull()
    }

    @ParameterizedTest
    @MethodSource("plainTextDateFormats")
    fun testExtractEventTimestamp_plainText_withDateFormat_parsesCorrectly(raw: String, format: String, expectedEpochMs: Long) {
        val result = helper.extractEventTimestamp(raw, LogFormat.PLAIN_TEXT, DateConfig(format = format))
        assertThat(result).isEqualTo(Instant.fromEpochMilliseconds(expectedEpochMs))
    }

    @Test
    fun testExtractEventTimestamp_plainText_parseFailure_fallsBackToNow() {
        val result = helper.extractEventTimestamp("not a timestamp", LogFormat.PLAIN_TEXT, DateConfig(format = DATE_FORMAT_NO_TZ))
        assertThat(result).isNull()
    }

    @Test
    fun testExtractEventTimestamp_json_noFieldSpecified_usesDefaultTimestampField() {
        val raw = """{"timestamp":"2023-11-14 12:00:00","message":"hello"}"""
        val result = helper.extractEventTimestamp(raw, LogFormat.JSON, DateConfig(format = DATE_FORMAT_NO_TZ))
        assertThat(result).isEqualTo(Instant.fromEpochMilliseconds(EPOCH_MS_2023_11_14_NOON_UTC))
    }

    @Test
    fun testExtractEventTimestamp_json_epochMillis() {
        val raw = """{"ts":$EPOCH_MS_2023_11_14_NOON_UTC,"message":"hello"}"""
        val result = helper.extractEventTimestamp(raw, LogFormat.JSON, DateConfig(field = "ts", isEpochMillis = true))
        assertThat(result).isEqualTo(Instant.fromEpochMilliseconds(EPOCH_MS_2023_11_14_NOON_UTC))
    }

    @ParameterizedTest
    @MethodSource("jsonDateFormats")
    fun testExtractEventTimestamp_json_withFormat_parsesCorrectly(raw: String, format: String, expectedEpochMs: Long) {
        val result = helper.extractEventTimestamp(raw, LogFormat.JSON, DateConfig(field = "ts", format = format))
        assertThat(result).isEqualTo(Instant.fromEpochMilliseconds(expectedEpochMs))
    }

    @Test
    fun testExtractEventTimestamp_json_fieldNotFound_fallsBackToNow() {
        val raw = """{"message":"hello"}"""
        val result = helper.extractEventTimestamp(raw, LogFormat.JSON, DateConfig(field = "ts", format = DATE_FORMAT_NO_TZ))
        assertThat(result).isNull()
    }

    @Test
    fun testExtractEventTimestamp_json_parseFailure_fallsBackToNow() {
        val raw = """{"ts":"not-a-date","message":"hello"}"""
        val result = helper.extractEventTimestamp(raw, LogFormat.JSON, DateConfig(field = "ts", format = DATE_FORMAT_NO_TZ))
        assertThat(result).isNull()
    }

    @Test
    fun testExtractEventTimestamp_json_multipleMatchesDueToNestedObject_fallsBackToNow() {
        val raw = """{"outer":{"ts":"2023-11-14 12:00:00"},"ts":"2023-11-14 12:00:00"}"""
        val result = helper.extractEventTimestamp(raw, LogFormat.JSON, DateConfig(field = "ts", format = DATE_FORMAT_NO_TZ))
        assertThat(result).isNull()
    }

    @Test
    fun testExtractEventTimestamp_logfmt_noFieldSpecified_usesDefaultTimestampField() {
        val raw = "timestamp=2023-11-14T12:00:00 message=hello"
        val result = helper.extractEventTimestamp(raw, LogFormat.LOGFMT, DateConfig(format = "yyyy-MM-dd'T'HH:mm:ss"))
        assertThat(result).isEqualTo(Instant.fromEpochMilliseconds(EPOCH_MS_2023_11_14_NOON_UTC))
    }

    @Test
    fun testExtractEventTimestamp_logfmt_epochMillis() {
        val raw = "level=info ts=$EPOCH_MS_2023_11_14_NOON_UTC message=hello"
        val result = helper.extractEventTimestamp(raw, LogFormat.LOGFMT, DateConfig(field = "ts", isEpochMillis = true))
        assertThat(result).isEqualTo(Instant.fromEpochMilliseconds(EPOCH_MS_2023_11_14_NOON_UTC))
    }

    @ParameterizedTest
    @MethodSource("logfmtDateFormats")
    fun testExtractEventTimestamp_logfmt_withFormat_parsesCorrectly(raw: String, format: String, expectedEpochMs: Long) {
        val result = helper.extractEventTimestamp(raw, LogFormat.LOGFMT, DateConfig(field = "ts", format = format))
        assertThat(result).isEqualTo(Instant.fromEpochMilliseconds(expectedEpochMs))
    }

    @Test
    fun testExtractEventTimestamp_logfmt_fieldNotFound_fallsBackToNow() {
        val raw = "level=info message=hello"
        val result = helper.extractEventTimestamp(raw, LogFormat.LOGFMT, DateConfig(field = "ts", format = DATE_FORMAT_NO_TZ))
        assertThat(result).isNull()
    }

    @Test
    fun testExtractEventTimestamp_logfmt_parseFailure_fallsBackToNow() {
        val raw = "ts=not-a-date message=hello"
        val result = helper.extractEventTimestamp(raw, LogFormat.LOGFMT, DateConfig(field = "ts", format = DATE_FORMAT_NO_TZ))
        assertThat(result).isNull()
    }

    @Test
    fun testArchive_movesFileToArchivedLogsAndInvalidatesListingCache() {
        val filesConfig = config("app.log*")
        val file = createFile("app.log.1")
        createFile("app.log.2")

        // Prime the cache so we can verify invalidation
        val beforeArchive = helper.findMatchingFilesInOrder(filesConfig)
        assertThatFileNames(beforeArchive).containsExactly("app.log.1", "app.log.2")

        helper.archive(file, filesConfig)

        assertThat(tempDir.resolve("app.log.1")).doesNotExist()
        assertThat(tempDir.resolve("archived-logs/app.log.1")).exists()

        // Listing cache was invalidated: the next call reflects the moved file without waiting for expiry
        val afterArchive = helper.findMatchingFilesInOrder(filesConfig)
        assertThatFileNames(afterArchive).containsExactly("app.log.2")
    }

    companion object {
        private const val EPOCH_MS_2023_11_14_NOON_UTC = 1699963200000L
        private const val EPOCH_MS_2023_11_14_NOON_PLUS0200 = 1699956000000L // 10:00 UTC
        private const val DATE_FORMAT_NO_TZ = "yyyy-MM-dd HH:mm:ss"
        private const val DATE_FORMAT_WITH_TZ = "yyyy-MM-dd HH:mm:ss Z"

        @JvmStatic
        fun plainTextDateFormats(): Stream<Arguments> = Stream.of(
            Arguments.of("2023-11-14 12:00:00,INFO logger.name,hello world", DATE_FORMAT_NO_TZ, EPOCH_MS_2023_11_14_NOON_UTC),
            Arguments.of("2023-11-14 12:00:00 +0200,INFO logger.name,hello world", DATE_FORMAT_WITH_TZ, EPOCH_MS_2023_11_14_NOON_PLUS0200),
        )

        @JvmStatic
        fun jsonDateFormats(): Stream<Arguments> = Stream.of(
            Arguments.of("""{"ts":"2023-11-14 12:00:00","msg":"x"}""", DATE_FORMAT_NO_TZ, EPOCH_MS_2023_11_14_NOON_UTC),
            Arguments.of("""{"ts":"2023-11-14 12:00:00 +0200","msg":"x"}""", DATE_FORMAT_WITH_TZ, EPOCH_MS_2023_11_14_NOON_PLUS0200),
        )

        @JvmStatic
        fun logfmtDateFormats(): Stream<Arguments> = Stream.of(
            Arguments.of("ts=2023-11-14T12:00:00 msg=x", "yyyy-MM-dd'T'HH:mm:ss", EPOCH_MS_2023_11_14_NOON_UTC),
            Arguments.of("""ts="2023-11-14 12:00:00 +0200" msg=x""", DATE_FORMAT_WITH_TZ, EPOCH_MS_2023_11_14_NOON_PLUS0200),
        )
    }

    private fun assertThatFileNames(result: List<Path>) = assertThat(result.map { it.fileName.toString() })

    private fun config(glob: String) = FilesConfig(root = tempDir.toString(), glob = glob)
    private fun createFile(name: String): Path = Files.createFile(tempDir.resolve(name))
    private fun createDir(name: String): Path = Files.createDirectory(tempDir.resolve(name))
}

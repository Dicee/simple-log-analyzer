package com.simpleloganalyzer.ingestion.persistence.metadata

import com.simpleloganalyzer.ingestion.exception.ErrorCode
import com.simpleloganalyzer.ingestion.exception.assertThrowsBadRequestException
import com.simpleloganalyzer.ingestion.service.LogFileService
import com.simpleloganalyzer.testcommons.assertions.MoreAssertions.assertThatThrownBy
import io.mockk.every
import kotlin.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class LogFileServiceTest : DatabaseTestBase() {
    private lateinit var service: LogFileService

    private val t0 = Instant.fromEpochSeconds(100)
    private val t1 = Instant.fromEpochSeconds(200)

    @BeforeEach
    fun setUpService() {
        service = LogFileService(fileDaoSpy, streamDaoSpy, db)
    }

    @Test
    fun testCreate() {
        setUpStream()

        assertThat(service.create(TEST_GROUP, TEST_STREAM, FILE_1, t0, t1))
            .isEqualTo(newLogFile(TEST_GROUP, TEST_STREAM, FILE_1, t0, t1))

        assertThat(fileDaoSpy.list(TEST_GROUP, TEST_STREAM, 10, null))
            .containsExactly(newLogFile(TEST_GROUP, TEST_STREAM, FILE_1, t0, t1))
    }

    @Test
    fun testCreate_equalTimestamps() {
        setUpStream()
        assertThat(service.create(TEST_GROUP, TEST_STREAM, FILE_1, t0, t0))
            .isEqualTo(newLogFile(TEST_GROUP, TEST_STREAM, FILE_1, t0, t0))

        assertThat(fileDaoSpy.list(TEST_GROUP, TEST_STREAM, 10, null))
            .containsExactly(newLogFile(TEST_GROUP, TEST_STREAM, FILE_1, t0, t0))
    }

    @Test
    fun testCreate_unexpectedException() {
        setUpStream()

        val e = IllegalStateException("Oh no!!")
        every { fileDaoSpy.create(any(), any(), any(), any(), any()) }.throws(e)

        assertThatThrownBy { service.create(TEST_GROUP, TEST_STREAM, FILE_1, t0, t1) }.isSameAs(e)
    }

    @Test
    fun testCreate_alreadyExists() {
        setUpStream()
        service.create(TEST_GROUP, TEST_STREAM, FILE_1, t0, t1)

        assertThrowsBadRequestException(
            ErrorCode.LOG_FILE_ALREADY_EXISTS,
            "Log file '$FILE_1' already exists in stream '$TEST_GROUP/$TEST_STREAM'",
            recursive = false,
        ) {
            service.create(TEST_GROUP, TEST_STREAM, FILE_1, t0, t1)
        }
    }

    @Test
    fun testCreate_invalidFileName() {
        setUpStream()
        assertThrowsBadRequestException(
            ErrorCode.INVALID_NAME,
            "file name must contain only letters, digits, '.', '_' or '-' but was: bad name!",
        ) {
            service.create(TEST_GROUP, TEST_STREAM, "bad name!", t0, t1)
        }
    }

    @Test
    fun testCreate_firstTimestampAfterLast() {
        setUpStream()
        assertThrowsBadRequestException(ErrorCode.BAD_REQUEST, "firstTimestamp must be <= lastTimestamp") {
            service.create(TEST_GROUP, TEST_STREAM, FILE_1, t1, t0)
        }
    }

    @Test
    fun testCreate_streamNotFound() {
        createGroup(TEST_GROUP)
        assertThrowsBadRequestException(
            ErrorCode.LOG_STREAM_NOT_FOUND,
            "Log stream '$TEST_STREAM' does not exist in log group '$TEST_GROUP'",
        ) {
            service.create(TEST_GROUP, TEST_STREAM, FILE_1, t0, t1)
        }
    }

    @Test
    fun testList_empty() {
        setUpStream()
        assertThat(service.list(TEST_GROUP, TEST_STREAM, 10, null)).isEmpty()
    }

    @Test
    fun testList_singleFile() {
        setUpStream()
        service.create(TEST_GROUP, TEST_STREAM, FILE_1, t0, t1)
        assertThat(service.list(TEST_GROUP, TEST_STREAM, 10, null))
            .containsExactly(newLogFile(TEST_GROUP, TEST_STREAM, FILE_1, t0, t1))
    }

    @Test
    fun testList_streamNotFound() {
        createGroup(TEST_GROUP)
        assertThrowsBadRequestException(
            ErrorCode.LOG_STREAM_NOT_FOUND,
            "Log stream '$TEST_STREAM' does not exist in log group '$TEST_GROUP'",
        ) {
            service.list(TEST_GROUP, TEST_STREAM, 10, null)
        }
    }

    @Test
    fun testList_isolatedToStream() {
        setUpStream()
        createStream(TEST_GROUP, STREAM_2)

        service.create(TEST_GROUP, TEST_STREAM, FILE_1, t0, t1)
        service.create(TEST_GROUP, STREAM_2, FILE_2, t0, t1)

        assertThat(service.list(TEST_GROUP, TEST_STREAM, 10, null))
            .containsExactly(newLogFile(TEST_GROUP, TEST_STREAM, FILE_1, t0, t1))
    }

    @Test
    fun testList_pagination() {
        setUpStream()
        val names = (0..5).map { "file-%02d".format(it) }
        for (name in names.shuffled()) service.create(TEST_GROUP, TEST_STREAM, name, t0, t1)

        val pageSize = 3
        val expected = names.map { newLogFile(TEST_GROUP, TEST_STREAM, it, t0, t1) }

        assertThat(service.list(TEST_GROUP, TEST_STREAM, pageSize, null)).isEqualTo(expected.subList(0, 3))
        assertThat(service.list(TEST_GROUP, TEST_STREAM, pageSize, minFileName = "file-02")).isEqualTo(expected.subList(3, 6))
        assertThat(service.list(TEST_GROUP, TEST_STREAM, pageSize, minFileName = "file-05")).isEmpty()
    }

    private fun setUpStream() {
        createGroup(TEST_GROUP)
        createStream(TEST_GROUP, TEST_STREAM)
    }
}

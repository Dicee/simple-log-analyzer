package com.simpleloganalyzer.ingestion.persistence.metadata

import com.simpleloganalyzer.ingestion.exception.ErrorCode
import com.simpleloganalyzer.ingestion.exception.assertThrowsBadRequestException
import com.simpleloganalyzer.ingestion.model.LogStream
import com.simpleloganalyzer.ingestion.service.LogStreamService
import com.simpleloganalyzer.testcommons.assertions.MoreAssertions
import io.mockk.every
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class LogStreamServiceTest : DatabaseTestBase() {
    private lateinit var service: LogStreamService

    @BeforeEach
    fun setUpService() {
        service = LogStreamService(groupDaoSpy, streamDaoSpy, fileDaoSpy, db)
    }

    @Test
    fun testCreateAndFind() {
        createGroup(TEST_GROUP)

        val expected = newLogStream(TEST_GROUP, TEST_STREAM)
        assertThat(service.create(TEST_GROUP, TEST_STREAM)).isEqualTo(expected)
        assertThat(streamDaoSpy.find(TEST_GROUP, TEST_STREAM)).isEqualTo(expected)
    }

    @Test
    fun testCreateAndFind_logGroupDoesNotExist() {
        assertThrowsBadRequestException(ErrorCode.LOG_GROUP_NOT_FOUND, "Log group 'testGroup' does not exist") {
            service.create(TEST_GROUP, TEST_STREAM)
        }
    }

    @Test
    fun testCreate_duplicate() {
        createGroup(TEST_GROUP)

        val logStream = newLogStream(TEST_GROUP, TEST_STREAM)
        assertThat(service.create(TEST_GROUP, TEST_STREAM)).isEqualTo(logStream)

        val msg = "Log stream 'testStream' already exists in log group 'testGroup'"
        assertThrowsBadRequestException(ErrorCode.LOG_STREAM_ALREADY_EXISTS, msg, recursive = false) {
            service.create(TEST_GROUP, TEST_STREAM)
        }
    }

    @Test
    fun testCreate_invalidName() {
        assertThrowsBadRequestException(ErrorCode.INVALID_NAME, "log stream name must contain only letters, digits, '.', '_' or '-' but was: ;;lldd") {
            service.create(TEST_GROUP, ";;lldd")
        }
    }

    @Test
    fun testCreate_unexpectedException() {
        createGroup(TEST_GROUP)

        val e = IllegalStateException("Oh no!!")
        every { streamDaoSpy.create(any(), any()) }
            .throws(e)

        MoreAssertions.assertThatThrownBy { service.create(TEST_GROUP, TEST_STREAM) }.isSameAs(e)
    }

    @Test
    fun testFind_missing() {
        assertThat(streamDaoSpy.find(TEST_GROUP, TEST_STREAM)).isNull()
    }

    @Test
    fun testList_logGroupDoesNotExist() {
        assertThrowsBadRequestException(ErrorCode.LOG_GROUP_NOT_FOUND, "Log group 'testGroup' does not exist") {
            service.list(TEST_GROUP, 10)
        }
    }

    @Test
    fun testList_emptyLogGroup() {
        createGroup(TEST_GROUP)

        assertThat(service.list(TEST_GROUP, 10)).isEmpty()
    }

    @Test
    fun testList_singleStream() {
        createGroup(TEST_GROUP)

        val expected = newLogStream(TEST_GROUP, TEST_STREAM)
        assertThat(service.create(TEST_GROUP, TEST_STREAM)).isEqualTo(expected)
        assertThat(service.list(TEST_GROUP, 10)).containsExactly(expected)
    }

    @Test
    fun testList_multipleStreams() {
        createGroup(TEST_GROUP)
        createGroup(OTHER_GROUP)

        val stream1 = newLogStream(TEST_GROUP, STREAM_1)
        val stream2 = newLogStream(TEST_GROUP, STREAM_2)
        val otherStream = newLogStream(OTHER_GROUP, OTHER_STREAM)

        assertThat(service.create(OTHER_GROUP, OTHER_STREAM)).isEqualTo(otherStream)
        assertThat(service.create(TEST_GROUP, STREAM_1)).isEqualTo(stream1)
        assertThat(service.create(TEST_GROUP, STREAM_2)).isEqualTo(stream2)

        assertThat(service.list(TEST_GROUP, 10)).containsExactly(stream1, stream2)
    }

    @Test
    fun testList_pagination() {
        createGroup(TEST_GROUP)

        val shuffledNames = numberedStreamNames(0..10).shuffled()
        for (name in shuffledNames) service.create(TEST_GROUP, name)

        val pageSize = 5
        assertThat(service.list(TEST_GROUP, pageSize)).isEqualTo(numberedStreams(0..4))
        assertThat(service.list(TEST_GROUP, pageSize, minStreamName = "stream-004")).isEqualTo(numberedStreams(5..9))
        assertThat(service.list(TEST_GROUP, pageSize, minStreamName = "stream-009")).isEqualTo(numberedStreams(10..10))
        assertThat(service.list(TEST_GROUP, pageSize, minStreamName = "stream-010")).isEmpty()
    }

    @Test
    fun testCascadeDelete_groupDoesNotExist() {
        assertThrowsBadRequestException(ErrorCode.LOG_GROUP_NOT_FOUND, "Log group 'testGroup' does not exist") {
            service.cascadeDelete(TEST_GROUP, TEST_STREAM)
        }
    }

    @Test
    fun testCascadeDelete_streamDoesNotExist() {
        createGroup(TEST_GROUP)

        assertThrowsBadRequestException(ErrorCode.LOG_STREAM_NOT_FOUND, "Log stream 'testStream' does not exist in log group 'testGroup'") {
            service.cascadeDelete(TEST_GROUP, TEST_STREAM)
        }
    }

    @Test
    fun testCascadeDelete_noLogFiles() {
        createGroup(TEST_GROUP)
        service.create(TEST_GROUP, TEST_STREAM)
        service.cascadeDelete(TEST_GROUP, TEST_STREAM)

        assertThat(streamDaoSpy.find(TEST_GROUP, TEST_STREAM)).isNull()
        assertThat(groupDaoSpy.findByName(TEST_GROUP)).isNotNull() // wasn't deleted by the operation
    }

    @Test
    fun testCascadeDelete_withLogFilesAndOtherStream() {
        createGroup(TEST_GROUP)
        service.create(TEST_GROUP, STREAM_1)
        service.create(TEST_GROUP, STREAM_2)

        fileDaoSpy.create(TEST_GROUP, STREAM_1, FILE_1, clock.now(), clock.now())
        fileDaoSpy.create(TEST_GROUP, STREAM_1, FILE_2, clock.now(), clock.now())
        fileDaoSpy.create(TEST_GROUP, STREAM_2, FILE_1, clock.now(), clock.now())

        service.cascadeDelete(TEST_GROUP, STREAM_1)

        assertThat(streamDaoSpy.find(TEST_GROUP, STREAM_1)).isNull()

        assertThat(streamDaoSpy.find(TEST_GROUP, STREAM_2)).isNotNull() // wasn't deleted by the operation
        assertThat(fileDaoSpy.list(TEST_GROUP, STREAM_2, 5)).hasSize(1) // wasn't deleted by the operation
        assertThat(groupDaoSpy.findByName(TEST_GROUP)).isNotNull() // wasn't deleted by the operation
    }

    private fun numberedStreams(range: IntRange): List<LogStream> = numberedStreamNames(range).map { newLogStream(TEST_GROUP, it) }
    private fun numberedStreamNames(range: IntRange): List<String> = range.map { "stream-%03d".format(it) }
}
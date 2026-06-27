package com.simpleloganalyzer.ingestion.persistence.metadata

import com.simpleloganalyzer.ingestion.exception.ErrorCode
import com.simpleloganalyzer.ingestion.exception.assertThrowsBadRequestException
import com.simpleloganalyzer.ingestion.model.CompressionMode
import com.simpleloganalyzer.ingestion.model.LogFormat
import com.simpleloganalyzer.ingestion.service.LogGroupService
import com.simpleloganalyzer.testcommons.assertions.MoreAssertions.assertThatThrownBy
import io.mockk.every
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class LogGroupServiceTest : DatabaseTestBase() {
    private lateinit var service: LogGroupService

    @BeforeEach
    fun setUpService() {
        service = LogGroupService(groupDaoSpy, streamDaoSpy, db)
    }

    @Test
    fun testCreate() {
        val expected = newLogGroup(TEST_GROUP)
        assertThat(service.create(TEST_GROUP, null, LogFormat.LOGFMT, CompressionMode.NONE)).isEqualTo(expected)
        assertThat(groupDaoSpy.findByName(TEST_GROUP)).isEqualTo(expected)
    }

    @Test
    fun testCreate_withDescription() {
        val desc = "my log group"
        val expected = newLogGroup(TEST_GROUP, desc).copy(format = LogFormat.JSON, compression = CompressionMode.GZIP)

        assertThat(service.create(TEST_GROUP, desc, LogFormat.JSON, CompressionMode.GZIP)).isEqualTo(expected)
        assertThat(groupDaoSpy.findByName(TEST_GROUP)).isEqualTo(expected)
    }

    @Test
    fun testCreate_unexpectedException() {
        val e = IllegalStateException("Oh no!!")
        every { groupDaoSpy.create(any(), any(), any(), any()) }.throws(e)

        assertThatThrownBy { service.create(TEST_GROUP, null, LogFormat.LOGFMT, CompressionMode.NONE) }.isSameAs(e)
    }

    @Test
    fun testCreate_alreadyExists() {
        service.create(TEST_GROUP, null, LogFormat.LOGFMT, CompressionMode.NONE)

        assertThrowsBadRequestException(
            ErrorCode.LOG_GROUP_ALREADY_EXISTS,
            "Log group '$TEST_GROUP' already exists",
            recursive = false,
        ) {
            service.create(TEST_GROUP, null, LogFormat.LOGFMT, CompressionMode.NONE)
        }
    }

    @Test
    fun testCreate_invalidName() {
        assertThrowsBadRequestException(
            ErrorCode.INVALID_NAME,
            "log group name must contain only letters, digits, '.', '_' or '-' but was: bad name!",
        ) {
            service.create("bad name!", null, LogFormat.LOGFMT, CompressionMode.NONE)
        }
    }

    @Test
    fun testCreate_descriptionTooLong() {
        val longDesc = "x".repeat(LONG_STRING_MAX_LENGTH + 1)
        assertThrowsBadRequestException(ErrorCode.BAD_REQUEST, "description must be at most $LONG_STRING_MAX_LENGTH characters") {
            service.create(TEST_GROUP, longDesc, LogFormat.LOGFMT, CompressionMode.NONE)
        }
    }

    @Test
    fun testCreate_descriptionAtMaxLength() {
        val desc = "x".repeat(LONG_STRING_MAX_LENGTH)
        assertThat(service.create(TEST_GROUP, desc, LogFormat.LOGFMT, CompressionMode.NONE).description).isEqualTo(desc)
    }

    @Test
    fun testGet_existing() {
        service.create(TEST_GROUP, null, LogFormat.LOGFMT, CompressionMode.NONE)
        assertThat(service.get(TEST_GROUP)).isEqualTo(newLogGroup(TEST_GROUP))
    }

    @Test
    fun testGet_notFound() {
        assertThrowsBadRequestException(ErrorCode.LOG_GROUP_NOT_FOUND, "Log group '$TEST_GROUP' does not exist") {
            service.get(TEST_GROUP)
        }
    }

    @Test
    fun testDelete_emptyGroup() {
        service.create(TEST_GROUP, null, LogFormat.LOGFMT, CompressionMode.NONE)
        service.delete(TEST_GROUP)
        assertThat(groupDaoSpy.findByName(TEST_GROUP)).isNull()
    }

    @Test
    fun testDelete_groupNotFound() {
        assertThrowsBadRequestException(ErrorCode.LOG_GROUP_NOT_FOUND, "Log group '$TEST_GROUP' does not exist") {
            service.delete(TEST_GROUP)
        }
    }

    @Test
    fun testDelete_groupNotEmpty() {
        service.create(TEST_GROUP, null, LogFormat.LOGFMT, CompressionMode.NONE)
        createStream(TEST_GROUP, TEST_STREAM)

        assertThrowsBadRequestException(ErrorCode.LOG_GROUP_NOT_EMPTY, "Log group '$TEST_GROUP' is not empty",) {
            service.delete(TEST_GROUP)
        }
        assertThat(groupDaoSpy.findByName(TEST_GROUP)).isNotNull()
    }
}

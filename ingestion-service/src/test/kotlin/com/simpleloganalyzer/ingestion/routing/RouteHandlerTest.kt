package com.simpleloganalyzer.ingestion.routing

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ThrowableProxy
import com.simpleloganalyzer.ingestion.exception.BadRequestException
import com.simpleloganalyzer.ingestion.exception.ErrorCode
import com.simpleloganalyzer.ingestion.exception.InternalServiceFailureException
import com.simpleloganalyzer.testcommons.assertions.MoreAssertions
import com.simpleloganalyzer.testcommons.logging.LogBackLogger
import com.simpleloganalyzer.testcommons.logging.RecordingAppender
import com.simpleloganalyzer.testcommons.logging.SimpleLogEvent
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.routing.*
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean

private val logger = LoggerFactory.getLogger("RouteHandler") as LogBackLogger

class RouteHandlerTest {
    private val request = mockk<RoutingRequest>(relaxed = true)
    private val call = mockk<RoutingCall>(relaxed = true)
    private val context = RoutingContext(call)
    private val pathParams = mockk<Parameters>()

    private lateinit var recordingAppender: RecordingAppender

    @BeforeEach
    fun setUp() {
        every { call.request } returns request
        every { call.parameters } returns pathParams
        every { request.httpMethod } returns HttpMethod.Get
        every { request.uri } returns "/test"

        recordingAppender = RecordingAppender.attachedToLogger(logger)
    }

    @AfterEach
    fun tearDown() {
        recordingAppender.detachFromLogger(logger)
    }

    @Test
    fun testPathParam_present() {
        every { pathParams["name"] } returns "my-value"
        assertThat(call.pathParam("name")).isEqualTo("my-value")
    }

    @Test
    fun testPathParam_missing() {
        every { pathParams["name"] } returns null
        MoreAssertions.assertThatThrownBy { call.pathParam("name") }
            .isLike(InternalServiceFailureException("missing path parameter 'name' — route declaration is out of sync"))
    }

    @Test
    fun testHandle_success() {
        val called = AtomicBoolean()
        runBlocking { context.handle("op") { called.set(true) } }

        assertThat(called).isTrue
        assertThat(recordingAppender.events).satisfiesExactly(
            { assertThat(it).isEqualTo(expectedEvent(Level.INFO, "[op] GET /test")) },
            { assertThat(it.toString()).startsWith("RouteHandler - INFO,[{}],[op] completed in") },
        )
    }

    @Test
    fun testHandle_rethrowsBadRequestException() {
        val e = BadRequestException(ErrorCode.BAD_REQUEST, "bad input")
        assertThatThrownBy { runBlocking { context.handle("op") { throw e } } }.isSameAs(e)
        assertThat(recordingAppender.events).containsExactly(
            expectedEvent(Level.INFO, "[op] GET /test"),
            expectedEvent(Level.WARN,"[op] bad request: bad input"),
        )

        // we voluntarily don't log 4xx's stacktrace
        assertThat(recordingAppender.events.last().thrown).isNull()
    }

    @Test
    fun testHandle_rethrowsInternalServiceFailureException() {
        val e = InternalServiceFailureException("boom")

        assertThatThrownBy { runBlocking { context.handle("op") { throw e } } }.isSameAs(e)
        assertThat(recordingAppender.events).containsExactly(
            expectedEvent(Level.INFO, "[op] GET /test"),
            expectedEvent(Level.ERROR,"[op] internal failure: com.simpleloganalyzer.ingestion.exception.InternalServiceFailureException: boom"),
        )

        val thrown = (recordingAppender.events.last().thrown as ThrowableProxy).throwable
        MoreAssertions.assertThatThrowable(thrown).isLike(InternalServiceFailureException("boom"))
    }

    @Test
    fun testHandle_wrapsUnexpectedThrowable() {
        val original = RuntimeException("surprise")

        MoreAssertions.assertThatThrownBy { runBlocking { context.handle("op") { throw original } } }
            .isLike(InternalServiceFailureException("Unexpected failure in op: java.lang.RuntimeException: surprise", original))

        assertThat(recordingAppender.events).containsExactly(
            expectedEvent(Level.INFO, "[op] GET /test"),
            expectedEvent(Level.ERROR,"[op] unexpected failure: java.lang.RuntimeException: surprise"),
        )

        val thrown = (recordingAppender.events.last().thrown as ThrowableProxy).throwable
        MoreAssertions.assertThatThrowable(thrown).isLike(original)
    }

    private fun expectedEvent(level: Level, msg: String): SimpleLogEvent = SimpleLogEvent("RouteHandler", level, msg)
}

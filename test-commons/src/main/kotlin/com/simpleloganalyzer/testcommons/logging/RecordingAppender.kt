package com.simpleloganalyzer.testcommons.logging

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.IThrowableProxy
import ch.qos.logback.core.AppenderBase
import org.slf4j.Logger
import org.slf4j.LoggerFactory

typealias LogBackLogger = ch.qos.logback.classic.Logger

/** An appender which stores all the events appended to it so that they can be compared to expectations in tests. */
class RecordingAppender : AppenderBase<ILoggingEvent>() {
    private val _events: MutableList<SimpleLogEvent> = mutableListOf()

    val events: List<SimpleLogEvent> = _events
    val messages: List<String>
        get() = _events.map { event: SimpleLogEvent -> formatLogMessage(event) }

    init { name = "RecordingAppender" }

    override fun append(logEvent: ILoggingEvent) {
        _events.add(SimpleLogEvent.from(logEvent))
    }

    fun detachFromLogger(logger: LogBackLogger) = logger.detachAppender(this)
    fun clearEvents() = _events.clear()

    companion object {
        val ROOT_LOGGER: Logger = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as LogBackLogger

        fun attachedToLogger(logger: LogBackLogger): RecordingAppender {
            val appender = RecordingAppender()
            logger.addAppender(appender)
            appender.start()
            return appender
        }

        private fun formatLogMessage(event: SimpleLogEvent): String {
            val sb: StringBuilder = StringBuilder(event.formattedMessage)
            if (event.thrown != null) sb.append('\n').append(event.thrown)
            return sb.toString()
        }
    }
}

/** Simplified [LogEvent] that can easily be compared in tests */
class SimpleLogEvent(
    val loggerName: String,
    val level: Level,
    val formattedMessage: String,
    val contextData: Map<String, String> = mapOf(),
    val thrown: IThrowableProxy? = null,
) {
    companion object {
        fun forClass(clazz: Class<*>, level: Level, msg: String): SimpleLogEvent = forClass(clazz, level, msg, mapOf())

        fun forClass(clazz: Class<*>, level: Level, msg: String, context: Map<String, String>): SimpleLogEvent =
            SimpleLogEvent(normalizeLoggerName(clazz.getName()), level, msg, context, null)

        fun from(event: ILoggingEvent): SimpleLogEvent {
            return SimpleLogEvent(
                normalizeLoggerName(event.loggerName), event.level,
                event.formattedMessage, event.mdcPropertyMap, event.throwableProxy
            )
        }

        // handle inconsistencies between different versions of log4j relating to nested classes
        private fun normalizeLoggerName(loggerName: String): String {
            return loggerName.replace("$", ".")
        }
    }

    // voluntarily exclude `thrown` as most Throwable implementations do not implement equality
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as SimpleLogEvent

        if (loggerName != other.loggerName) return false
        if (level != other.level) return false
        if (formattedMessage != other.formattedMessage) return false
        if (contextData != other.contextData) return false

        return true
    }

    // voluntarily exclude `thrown` as most Throwable implementations do not implement hashCode
    override fun hashCode(): Int {
        var result = loggerName.hashCode()
        result = 31 * result + level.hashCode()
        result = 31 * result + formattedMessage.hashCode()
        result = 31 * result + contextData.hashCode()
        return result
    }

    override fun toString(): String {
        val throwableStr = if (thrown == null) "" else ",${thrown.className}: ${thrown.message}"
        return "$loggerName - $level,[$contextData],${formattedMessage}$throwableStr)"
    }
}
package com.simpleloganalyzer.ingestion.persistence.metadata

import com.simpleloganalyzer.ingestion.model.LogStream
import kotlin.time.Clock
import kotlin.time.toJavaInstant
import kotlin.time.toKotlinInstant
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * Never use directly in prod, always go through [com.simpleloganalyzer.ingestion.service.LogStreamService].
 */
internal class LogStreamDao(private val db: MetadataDatabase, private val clock: Clock = Clock.System) {
    fun create(logGroup: String, streamName: String): LogStream = transaction(db.readWrite) {
        val now = clock.now()
        LogStreams.insert {
            it[LogStreams.logGroup] = logGroup
            it[LogStreams.streamName] = streamName
            it[LogStreams.creationDate] = now.toJavaInstant()
        }
        LogStream(logGroup, streamName, now)
    }

    fun find(logGroup: String, streamName: String): LogStream? = readTransaction(db) {
        LogStreams.selectAll()
            .where { (LogStreams.logGroup eq logGroup) and (LogStreams.streamName eq streamName) }
            .limit(1)
            .firstOrNull()
            ?.toLogStream()
    }

    fun list(logGroup: String, pageSize: Int, minStreamName: String?): List<LogStream> = readTransaction(db) {
        LogStreams.selectAll()
            .where {
                val base = LogStreams.logGroup eq logGroup
                if (minStreamName != null) base and (LogStreams.streamName greater minStreamName) else base
            }
            .orderBy(LogStreams.streamName to SortOrder.ASC)
            .limit(pageSize)
            .map { it.toLogStream() }
        }

    fun hasAnyInGroup(logGroup: String): Boolean = readTransaction(db) {
        LogStreams.selectAll().where { LogStreams.logGroup eq logGroup }.limit(1).any()
    }

    /** Deletes only the stream row. Cascading to log files is the caller's responsibility. */
    fun delete(logGroup: String, streamName: String): Boolean = deleteTransaction(db) {
        LogStreams.deleteWhere { (LogStreams.logGroup eq logGroup) and (LogStreams.streamName eq streamName) } > 0
    }

    private fun ResultRow.toLogStream(): LogStream = LogStream(
        logGroup = this[LogStreams.logGroup],
        streamName = this[LogStreams.streamName],
        creationDate = this[LogStreams.creationDate].toKotlinInstant(),
    )
}

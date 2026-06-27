package com.simpleloganalyzer.ingestion.persistence.metadata

import com.simpleloganalyzer.ingestion.model.LogFile
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.time.toJavaInstant
import kotlin.time.toKotlinInstant
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * Never use directly in prod, always go through [com.simpleloganalyzer.ingestion.service.LogFileService].
 */
internal class LogFileDao(private val db: MetadataDatabase, private val clock: Clock = Clock.System) {
    fun create(logGroup: String, logStream: String, fileName: String, firstTimestamp: Instant, lastTimestamp: Instant): LogFile = transaction(db.readWrite) {
        val now = clock.now()
        LogFiles.insert {
            it[LogFiles.logGroup] = logGroup
            it[LogFiles.logStream] = logStream
            it[LogFiles.fileName] = fileName
            it[LogFiles.creationDate] = now.toJavaInstant()
            it[LogFiles.lastModifiedDate] = now.toJavaInstant()
            it[LogFiles.firstTimestamp] = firstTimestamp.toJavaInstant()
            it[LogFiles.lastTimestamp] = lastTimestamp.toJavaInstant()
        }
        LogFile(logGroup, logStream, fileName, now, now, firstTimestamp, lastTimestamp)
    }

    fun list(logGroup: String, logStream: String, pageSize: Int, minLogGroupName: String? = null): List<LogFile> = readTransaction(db) {
        LogFiles.selectAll()
            .where {
                val base = (LogFiles.logGroup eq logGroup) and (LogFiles.logStream eq logStream)
                if (minLogGroupName != null) base and (LogFiles.fileName greater minLogGroupName) else base
            }
            .orderBy(LogFiles.fileName to SortOrder.ASC)
            .limit(pageSize)
            .map { it.toLogFile() }
    }

    /** Cascade helper: deletes every file belonging to the given stream. Caller owns the transaction. */
    fun deleteAllInStream(logGroup: String, logStream: String): Int =
        LogFiles.deleteWhere { (LogFiles.logGroup eq logGroup) and (LogFiles.logStream eq logStream) }

    private fun ResultRow.toLogFile(): LogFile = LogFile(
        logGroup = this[LogFiles.logGroup],
        logStream = this[LogFiles.logStream],
        fileName = this[LogFiles.fileName],
        creationDate = this[LogFiles.creationDate].toKotlinInstant(),
        lastModifiedDate = this[LogFiles.lastModifiedDate].toKotlinInstant(),
        firstTimestamp = this[LogFiles.firstTimestamp].toKotlinInstant(),
        lastTimestamp = this[LogFiles.lastTimestamp].toKotlinInstant(),
    )
}

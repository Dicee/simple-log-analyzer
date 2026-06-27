package com.simpleloganalyzer.ingestion.service

import com.simpleloganalyzer.ingestion.exception.BadRequestException
import com.simpleloganalyzer.ingestion.exception.ErrorCode
import com.simpleloganalyzer.ingestion.exception.logGroupNotFoundException
import com.simpleloganalyzer.ingestion.exception.logStreamNotFoundException
import com.simpleloganalyzer.ingestion.model.LogStream
import com.simpleloganalyzer.ingestion.persistence.metadata.LogFileDao
import com.simpleloganalyzer.ingestion.persistence.metadata.LogGroupDao
import com.simpleloganalyzer.ingestion.persistence.metadata.LogStreamDao
import com.simpleloganalyzer.ingestion.persistence.metadata.MetadataDatabase
import com.simpleloganalyzer.ingestion.persistence.metadata.deleteTransaction
import com.simpleloganalyzer.ingestion.persistence.metadata.readTransaction
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.transactions.transaction
import org.sqlite.SQLiteErrorCode
import org.sqlite.SQLiteException

class LogStreamService internal constructor(
    private val groupDao: LogGroupDao,
    private val streamDao: LogStreamDao,
    private val fileDao: LogFileDao,
    private val db: MetadataDatabase,
) {
    fun create(logGroup: String, streamName: String): LogStream {
        validateName(streamName, "log stream name")
        return transaction(db.readWrite) {
            if (groupDao.findByName(logGroup) == null) throw logGroupNotFoundException(logGroup)
            try {
                streamDao.create(logGroup, streamName)
            } catch (e: ExposedSQLException) {
                if (e.cause is SQLiteException && (e.cause as SQLiteException).resultCode == SQLiteErrorCode.SQLITE_CONSTRAINT_PRIMARYKEY) {
                    throw BadRequestException(
                        ErrorCode.LOG_STREAM_ALREADY_EXISTS,
                        "Log stream '$streamName' already exists in log group '$logGroup'", e,
                    )
                }
                throw e
            }
        }
    }

    fun list(logGroup: String, pageSize: Int, minStreamName: String? = null): List<LogStream> = readTransaction(db) {
        if (groupDao.findByName(logGroup) == null) throw logGroupNotFoundException(logGroup)
        streamDao.list(logGroup, pageSize, minStreamName)
    }

    fun cascadeDelete(logGroup: String, logStream: String): Unit = deleteTransaction(db) {
        if (groupDao.findByName(logGroup) == null) throw logGroupNotFoundException(logGroup)
        fileDao.deleteAllInStream(logGroup, logStream)
        if (!streamDao.delete(logGroup, logStream)) throw logStreamNotFoundException(logGroup, logStream)
    }
}

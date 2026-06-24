package com.simpleloganalyzer.ingestion.service

import com.simpleloganalyzer.ingestion.exception.BadRequestException
import com.simpleloganalyzer.ingestion.exception.ErrorCode
import com.simpleloganalyzer.ingestion.model.LogStream
import com.simpleloganalyzer.ingestion.persistence.metadata.LogFileDao
import com.simpleloganalyzer.ingestion.persistence.metadata.LogGroupDao
import com.simpleloganalyzer.ingestion.persistence.metadata.LogStreamDao
import com.simpleloganalyzer.ingestion.persistence.metadata.deleteTransaction
import com.simpleloganalyzer.ingestion.persistence.metadata.readTransaction
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.transaction
import org.sqlite.SQLiteErrorCode
import org.sqlite.SQLiteException

class LogStreamService(
    private val streamDao: LogStreamDao,
    private val groupDao: LogGroupDao,
    private val fileDao: LogFileDao,
    private val db: Database,
) {

    fun create(logGroup: String, streamName: String): LogStream {
        validateName(streamName, "log stream name")
        return transaction(db) {
            if (groupDao.findByName(logGroup) == null) {
                throw BadRequestException(
                    ErrorCode.LOG_GROUP_NOT_FOUND, "Log group '$logGroup' does not exist",
                )
            }
            try {
                streamDao.create(logGroup, streamName)
            } catch (e: SQLiteException) {
                if (e.resultCode == SQLiteErrorCode.SQLITE_CONSTRAINT_PRIMARYKEY) {
                    throw BadRequestException(
                        ErrorCode.LOG_STREAM_ALREADY_EXISTS,
                        "Log stream '$streamName' already exists in log group '$logGroup'", e,
                    )
                }
                throw e
            }
        }
    }

    fun list(logGroup: String, pageSize: Int, pageToken: String?): List<LogStream> = readTransaction(db) {
        if (groupDao.findByName(logGroup) == null) {
            throw BadRequestException(
                ErrorCode.LOG_GROUP_NOT_FOUND, "Log group '$logGroup' does not exist",
            )
        }
        streamDao.list(logGroup, pageSize, pageToken)
    }

    fun cascadeDelete(logGroup: String, streamName: String): Unit = deleteTransaction(db) {
        fileDao.deleteAllInStream(logGroup, streamName)
        if (!streamDao.delete(logGroup, streamName)) {
            throw BadRequestException(ErrorCode.LOG_STREAM_NOT_FOUND, "Log stream '$streamName' does not exist in log group '$logGroup'")
        }
    }
}

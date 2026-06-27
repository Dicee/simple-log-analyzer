package com.simpleloganalyzer.ingestion.service

import com.simpleloganalyzer.ingestion.exception.BadRequestException
import com.simpleloganalyzer.ingestion.exception.ErrorCode
import com.simpleloganalyzer.ingestion.exception.logStreamNotFoundException
import com.simpleloganalyzer.ingestion.model.LogFile
import com.simpleloganalyzer.ingestion.persistence.metadata.LogFileDao
import com.simpleloganalyzer.ingestion.persistence.metadata.LogStreamDao
import com.simpleloganalyzer.ingestion.persistence.metadata.MetadataDatabase
import com.simpleloganalyzer.ingestion.persistence.metadata.readTransaction
import kotlin.time.Instant
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.transactions.transaction
import org.sqlite.SQLiteErrorCode
import org.sqlite.SQLiteException

class LogFileService internal constructor(
    private val fileDao: LogFileDao,
    private val streamDao: LogStreamDao,
    private val db: MetadataDatabase,
) {
    fun create(logGroup: String, logStream: String, fileName: String, firstTimestamp: Instant, lastTimestamp: Instant): LogFile {
        validateName(fileName, "file name")

        if (firstTimestamp > lastTimestamp) {
            throw BadRequestException(ErrorCode.BAD_REQUEST, "firstTimestamp must be <= lastTimestamp")
        }
        return transaction(db.readWrite) {
            if (streamDao.find(logGroup, logStream) == null) throw logStreamNotFoundException(logGroup, logStream)
            try {
                fileDao.create(logGroup, logStream, fileName, firstTimestamp, lastTimestamp)
            } catch (e: ExposedSQLException) {
                if (e.cause is SQLiteException && (e.cause as SQLiteException).resultCode == SQLiteErrorCode.SQLITE_CONSTRAINT_PRIMARYKEY) {
                    throw BadRequestException(
                        ErrorCode.LOG_FILE_ALREADY_EXISTS,
                        "Log file '$fileName' already exists in stream '$logGroup/$logStream'", e,
                    )
                }
                throw e
            }
        }
    }

    fun list(logGroup: String, logStream: String, pageSize: Int, minFileName: String?): List<LogFile> = readTransaction(db) {
        if (streamDao.find(logGroup, logStream) == null) {
            throw BadRequestException(
                ErrorCode.LOG_STREAM_NOT_FOUND,
                "Log stream '$logStream' does not exist in log group '$logGroup'",
            )
        }
        fileDao.list(logGroup, logStream, pageSize, minFileName)
    }
}

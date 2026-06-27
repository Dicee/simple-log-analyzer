package com.simpleloganalyzer.ingestion.service

import com.simpleloganalyzer.ingestion.exception.BadRequestException
import com.simpleloganalyzer.ingestion.exception.ErrorCode
import com.simpleloganalyzer.ingestion.exception.logGroupNotFoundException
import com.simpleloganalyzer.ingestion.model.CompressionMode
import com.simpleloganalyzer.ingestion.model.LogFormat
import com.simpleloganalyzer.ingestion.model.LogGroup
import com.simpleloganalyzer.ingestion.persistence.metadata.LONG_STRING_MAX_LENGTH
import com.simpleloganalyzer.ingestion.persistence.metadata.LogGroupDao
import com.simpleloganalyzer.ingestion.persistence.metadata.LogStreamDao
import com.simpleloganalyzer.ingestion.persistence.metadata.MetadataDatabase
import com.simpleloganalyzer.ingestion.persistence.metadata.deleteTransaction
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.sqlite.SQLiteErrorCode
import org.sqlite.SQLiteException

class LogGroupService internal constructor(
    private val groupDao: LogGroupDao,
    private val streamDao: LogStreamDao,
    private val db: MetadataDatabase,
) {
    fun create(name: String, description: String?, format: LogFormat, compression: CompressionMode): LogGroup {
        validateName(name, "log group name")
        if (description != null && description.length > LONG_STRING_MAX_LENGTH) {
            throw BadRequestException(
                ErrorCode.BAD_REQUEST, "description must be at most $LONG_STRING_MAX_LENGTH characters",
            )
        }
        return try {
            groupDao.create(name, format, compression, description)
        } catch (e: ExposedSQLException) {
            if (e.cause is SQLiteException && (e.cause as SQLiteException).resultCode == SQLiteErrorCode.SQLITE_CONSTRAINT_PRIMARYKEY) {
                throw BadRequestException(
                    ErrorCode.LOG_GROUP_ALREADY_EXISTS, "Log group '$name' already exists", e,
                )
            }
            throw e
        }
    }

    fun get(name: String): LogGroup = groupDao.findByName(name) ?:
        throw logGroupNotFoundException(name)

    /**
     * To be really sure that the user intended to make a deletion and that it is safe to do so, we do not cascade to
     * log streams and log files and ensure the log group is empty before being deleted.
     */
    fun delete(name: String): Unit = deleteTransaction(db) {
        if (streamDao.hasAnyInGroup(name)) {
            throw BadRequestException(ErrorCode.LOG_GROUP_NOT_EMPTY, "Log group '$name' is not empty")
        }
        if (!groupDao.delete(name)) throw logGroupNotFoundException(name)
    }
}

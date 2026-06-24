package com.simpleloganalyzer.ingestion.service

import com.simpleloganalyzer.ingestion.exception.BadRequestException
import com.simpleloganalyzer.ingestion.exception.ErrorCode
import com.simpleloganalyzer.ingestion.model.CompressionMode
import com.simpleloganalyzer.ingestion.model.LogFormat
import com.simpleloganalyzer.ingestion.model.LogGroup
import com.simpleloganalyzer.ingestion.persistence.metadata.LogGroupDao
import com.simpleloganalyzer.ingestion.persistence.metadata.LogStreamDao
import com.simpleloganalyzer.ingestion.persistence.metadata.deleteTransaction
import org.jetbrains.exposed.sql.Database
import org.sqlite.SQLiteErrorCode
import org.sqlite.SQLiteException

class LogGroupService(
    private val groupDao: LogGroupDao,
    private val streamDao: LogStreamDao,
    private val db: Database,
) {

    fun create(name: String, description: String?, format: LogFormat, compression: CompressionMode): LogGroup {
        validateName(name, "log group name")
        if (description != null && description.length > 500) {
            throw BadRequestException(
                ErrorCode.BAD_REQUEST, "description must be at most 500 characters",
            )
        }
        return try {
            groupDao.create(name, description, format, compression)
        } catch (e: SQLiteException) {
            if (e.resultCode == SQLiteErrorCode.SQLITE_CONSTRAINT_PRIMARYKEY) {
                throw BadRequestException(
                    ErrorCode.LOG_GROUP_ALREADY_EXISTS, "Log group '$name' already exists", e,
                )
            }
            throw e
        }
    }

    fun get(name: String): LogGroup = groupDao.findByName(name) ?:
        throw BadRequestException(ErrorCode.LOG_GROUP_NOT_FOUND, "Log group '$name' does not exist")

    /**
     * To be really sure that the user intended to make a deletion and that it is safe to do so, we do not cascade to
     * log streams and log files and ensure the log group is empty before being deleted.
     */
    fun delete(name: String): Unit = deleteTransaction(db) {
        if (streamDao.hasAnyInGroup(name)) {
            throw BadRequestException(
                ErrorCode.LOG_GROUP_NOT_EMPTY,
                "Log group '$name' is not empty; pass force=true to delete it along with its streams",
            )
        }
        if (!groupDao.delete(name)) {
            throw BadRequestException(
                ErrorCode.LOG_GROUP_NOT_FOUND, "Log group '$name' does not exist",
            )
        }
    }
}

package com.simpleloganalyzer.ingestion.persistence.metadata

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

const val DEFAULT_NAME_MAX_LENGTH = 50
const val LONG_STRING_MAX_LENGTH = 500
private const val SHORT_STRING_MAX_LENGTH = 16

object LogGroups : Table("log_groups") {
    val name = varchar("name", DEFAULT_NAME_MAX_LENGTH)
    val description = varchar("description", LONG_STRING_MAX_LENGTH).nullable()
    val creationDate = timestamp("creation_date")
    val format = varchar("format", SHORT_STRING_MAX_LENGTH)
    val compression = varchar("compression", SHORT_STRING_MAX_LENGTH)

    override val primaryKey = PrimaryKey(name)
}

object LogStreams : Table("log_streams") {
    val logGroup = varchar("log_group", DEFAULT_NAME_MAX_LENGTH).references(LogGroups.name)
    val streamName = varchar("stream_name", DEFAULT_NAME_MAX_LENGTH)
    val creationDate = timestamp("creation_date")

    override val primaryKey = PrimaryKey(logGroup, streamName)
}

object LogFiles : Table("log_files") {
    val logGroup = varchar("log_group", DEFAULT_NAME_MAX_LENGTH)
    val logStream = varchar("log_stream", DEFAULT_NAME_MAX_LENGTH)
    val fileName = varchar("file_name", DEFAULT_NAME_MAX_LENGTH)
    val creationDate = timestamp("creation_date")
    val lastModifiedDate = timestamp("last_modified_date")
    val firstTimestamp = timestamp("first_timestamp")
    val lastTimestamp = timestamp("last_timestamp")

    override val primaryKey = PrimaryKey(logGroup, logStream, fileName)

    init {
        foreignKey(logGroup, logStream, target = LogStreams.primaryKey)
    }
}

package com.simpleloganalyzer.ingestion.persistence.metadata

import com.simpleloganalyzer.ingestion.model.CompressionMode
import com.simpleloganalyzer.ingestion.model.LogFormat
import com.simpleloganalyzer.ingestion.model.LogGroup
import kotlin.time.Clock
import kotlin.time.toJavaInstant
import kotlin.time.toKotlinInstant
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * Never use directly in prod, always go through [com.simpleloganalyzer.ingestion.service.LogGroupService].
 */
internal class LogGroupDao(private val db: MetadataDatabase, private val clock: Clock = Clock.System) {
    fun create(name: String, format: LogFormat, compression: CompressionMode, description: String? = null): LogGroup = transaction(db.readWrite) {
        val now = clock.now()
        LogGroups.insert {
            it[LogGroups.name] = name
            it[LogGroups.description] = description
            it[LogGroups.creationDate] = now.toJavaInstant()
            it[LogGroups.format] = format.name
            it[LogGroups.compression] = compression.name
        }
        LogGroup(name, description, now, format, compression)
    }

    fun findByName(name: String): LogGroup? = readTransaction(db) {
        LogGroups.selectAll().where { LogGroups.name eq name }.limit(1).firstOrNull()?.toLogGroup()
    }

    fun list(pageSize: Int, minGroupName: String?): List<LogGroup> = readTransaction(db) {
        LogGroups.selectAll()
            .apply { if (minGroupName != null) where { LogGroups.name greater minGroupName } }
            .orderBy(LogGroups.name to SortOrder.ASC)
            .limit(pageSize)
            .map { it.toLogGroup() }
    }

    /** Deletes only the group row (no cascading). Caller owns the transaction. */
    fun delete(name: String): Boolean = LogGroups.deleteWhere { LogGroups.name eq name } > 0

    private fun ResultRow.toLogGroup(): LogGroup = LogGroup(
        name = this[LogGroups.name],
        description = this[LogGroups.description],
        creationDate = this[LogGroups.creationDate].toKotlinInstant(),
        format = LogFormat.valueOf(this[LogGroups.format]),
        compression = CompressionMode.valueOf(this[LogGroups.compression]),
    )
}

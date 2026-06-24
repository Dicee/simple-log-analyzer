package com.simpleloganalyzer.ingestion.persistence.metadata

import com.simpleloganalyzer.commons.logging.log
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.DatabaseConfig
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import org.sqlite.SQLiteDataSource
import java.sql.Connection

object LogMetadataStore {
    fun connect(dbPath: String): Database {
        log.info("Opening SQLite database at {}", dbPath)
        val dataSource = SQLiteDataSource().apply {
            url = "jdbc:sqlite:$dbPath"
            // Foreign keys are off by default in SQLite; turn them on for every connection.
            setEnforceForeignKeys(true)
        }

        val config = DatabaseConfig { defaultIsolationLevel = Connection.TRANSACTION_READ_COMMITTED }
        val db = Database.connect(dataSource, databaseConfig = config)
        transaction(db) {
            SchemaUtils.createMissingTablesAndColumns(LogGroups, LogStreams, LogFiles)
        }
        return db
    }
}

package com.simpleloganalyzer.ingestion.persistence.metadata

import com.simpleloganalyzer.commons.logging.log
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.DatabaseConfig
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import org.sqlite.SQLiteConfig
import org.sqlite.SQLiteDataSource
import java.nio.file.Path
import java.sql.Connection

class MetadataDatabase(val readWrite: Database, val readOnly: Database)

object LogMetadataStore {
    fun connect(dbPath: Path): MetadataDatabase = connect(dbPath.toAbsolutePath().toString())
    fun connect(dbPath: String): MetadataDatabase {
        log.info("Opening SQLite database at {}", dbPath)

        val readWriteDataSource = newSqliteDataSource(dbPath, false)
        val readOnlyDataSource = newSqliteDataSource(dbPath, true)

        val config = DatabaseConfig { defaultIsolationLevel = Connection.TRANSACTION_READ_COMMITTED }
        val readWriteDb = Database.connect(readWriteDataSource, databaseConfig = config)
        val readOnlyDb = Database.connect(readOnlyDataSource, databaseConfig = config)

        transaction(readWriteDb) {
            SchemaUtils.createMissingTablesAndColumns(LogGroups, LogStreams, LogFiles)
        }

        return MetadataDatabase(readWriteDb, readOnlyDb)
    }

    private fun newSqliteDataSource(dbPath: String, readOnly: Boolean): SQLiteDataSource = SQLiteDataSource().apply {
        val sqLiteConfig = SQLiteConfig()
        sqLiteConfig.setReadOnly(readOnly)

        url = "jdbc:sqlite:$dbPath"
        config = sqLiteConfig

        setEnforceForeignKeys(readOnly)
    }
}

package com.simpleloganalyzer.ingestion.persistence.metadata

import java.sql.Connection
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.transactions.transactionManager

/** Opens a read-only transaction on [db] using its default isolation level. */
fun <T> readTransaction(db: Database, statement: Transaction.() -> T): T =
    transaction(db.transactionManager.defaultIsolationLevel, readOnly = true, db = db, statement = statement)

/**
 * Opens a SERIALIZABLE transaction on [db]. Use for delete-with-precondition flows where a concurrent
 * insert between the check and the delete would violate the precondition (e.g. deleting a parent row
 * only if it has no children).
 */
fun <T> deleteTransaction(db: Database, statement: Transaction.() -> T): T =
    transaction(Connection.TRANSACTION_SERIALIZABLE, readOnly = false, db = db, statement = statement)

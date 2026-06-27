package com.simpleloganalyzer.ingestion.persistence.metadata

import java.sql.Connection
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.transactions.transactionManager

/** Opens a read-only transaction */
fun <T> readTransaction(db: MetadataDatabase, statement: Transaction.() -> T): T = db.readOnly.let {
    transaction(it.transactionManager.defaultIsolationLevel, readOnly = true, db = it, statement = statement)
}

/**
 * Opens a SERIALIZABLE transaction on [db]. Use for delete-with-precondition flows where a concurrent
 * insert between the check and the delete would violate the precondition (e.g. deleting a parent row
 * only if it has no children).
 */
fun <T> deleteTransaction(db: MetadataDatabase, statement: Transaction.() -> T): T =
    transaction(Connection.TRANSACTION_SERIALIZABLE, readOnly = false, db = db.readWrite, statement = statement)

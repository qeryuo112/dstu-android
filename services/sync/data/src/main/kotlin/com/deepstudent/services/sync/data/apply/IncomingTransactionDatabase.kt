package com.deepstudent.services.sync.data.apply

import com.deepstudent.platform.database.MigrationReport
import com.deepstudent.platform.database.SqlDatabase
import com.deepstudent.platform.database.SqlOperations
import com.deepstudent.platform.database.SqlReadOperations
import com.deepstudent.platform.database.SqlRow
import com.deepstudent.platform.database.SqlValue

/**
 * A transaction-bound view for composing existing owners on one thread and one database.
 * This view borrows the database; callers must not retain transaction operations or close it.
 */
class IncomingTransactionDatabase(private val delegate: SqlDatabase) : SqlDatabase {
    private val current = ThreadLocal<SqlOperations>()
    override val migrationReport: MigrationReport? get() = delegate.migrationReport

    fun <T> withCurrent(sql: SqlOperations, block: () -> T): T {
        check(current.get() == null)
        current.set(sql)
        return try {
            block()
        } finally {
            current.remove()
        }
    }

    override fun <T> transaction(block: SqlOperations.() -> T): T {
        val active = current.get()
        if (active != null) return active.block()
        return delegate.transaction {
            current.set(this)
            try {
                block(this)
            } finally {
                current.remove()
            }
        }
    }

    override fun execute(sql: String, args: List<SqlValue>) {
        val active = current.get()
        if (active != null) active.execute(sql, args) else delegate.execute(sql, args)
    }

    override fun <T> query(sql: String, args: List<SqlValue>, map: (SqlRow) -> T): List<T> {
        val active = current.get()
        return if (active != null) active.query(sql, args, map) else delegate.query(sql, args, map)
    }

    override fun <T> readSnapshot(block: SqlReadOperations.() -> T): T {
        check(current.get() == null) { "snapshot is not permitted inside a write transaction" }
        return delegate.readSnapshot(block)
    }

    override fun close() = error("transaction view does not own its database")
}

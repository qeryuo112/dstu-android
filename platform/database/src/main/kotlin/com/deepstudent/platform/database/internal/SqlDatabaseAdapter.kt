package com.deepstudent.platform.database.internal

import androidx.sqlite.SQLiteStatement
import com.deepstudent.platform.database.MigrationDiagnostic
import com.deepstudent.platform.database.MigrationException
import com.deepstudent.platform.database.SqlOperations
import com.deepstudent.platform.database.SqlReadOperations
import com.deepstudent.platform.database.SqlRow
import com.deepstudent.platform.database.SqlValue

/** No statement, row or operation scope may survive its callback or change threads. */
internal class StatementOperations(private val session: SqliteSession, private val before: () -> Unit = {}) : SqlOperations {
    override fun execute(sql: String, args: List<SqlValue>) {
        validatePublicStatement(sql)
        before()
        session.execBind(sql) { bind(it, args) }
    }

    override fun <T> query(sql: String, args: List<SqlValue>, map: (SqlRow) -> T): List<T> {
        validatePublicStatement(sql)
        before()
        return session.queryAll(sql, { bind(it, args) }) { statement ->
            val row = StatementRow(statement)
            try { map(row) } finally { row.active = false }
        }
    }

    private fun bind(statement: SQLiteStatement, args: List<SqlValue>) {
        args.forEachIndexed { offset, value ->
            val index = offset + 1
            when (value) {
                SqlValue.Null -> statement.bindNull(index)
                is SqlValue.Integer -> statement.bindLong(index, value.value)
                is SqlValue.Real -> statement.bindDouble(index, value.value)
                is SqlValue.Text -> statement.bindText(index, value.value)
                is SqlValue.Blob -> statement.bindBlob(index, value.copyBytes())
            }
        }
    }
}

private class StatementRow(private val statement: SQLiteStatement) : SqlRow {
    private val thread = Thread.currentThread()
    @Volatile var active = true
    override fun isNull(index: Int): Boolean = checked { statement.isNull(index) }
    override fun long(index: Int): Long = checked { statement.getLong(index) }
    override fun double(index: Int): Double = checked { statement.getDouble(index) }
    override fun text(index: Int): String = checked { statement.getText(index) }
    override fun blob(index: Int): ByteArray = checked { statement.getBlob(index) }
    private fun <T> checked(block: () -> T): T { checkScope(active, thread); return block() }
}

internal class ScopedWriter(private val delegate: SqlOperations) : SqlOperations {
    private val thread = Thread.currentThread()
    @Volatile var active = true
    override fun execute(sql: String, args: List<SqlValue>) {
        checkScope(active, thread)
        delegate.execute(sql, args)
    }
    override fun <T> query(sql: String, args: List<SqlValue>, map: (SqlRow) -> T): List<T> {
        checkScope(active, thread)
        return delegate.query(sql, args, map)
    }
}

internal class ScopedReader(private val delegate: SqlReadOperations) : SqlReadOperations {
    private val thread = Thread.currentThread()
    @Volatile var active = true
    override fun <T> query(sql: String, args: List<SqlValue>, map: (SqlRow) -> T): List<T> {
        checkScope(active, thread)
        return delegate.query(sql, args, map)
    }
}

internal fun checkScope(active: Boolean, thread: Thread) {
    if (!active || Thread.currentThread() !== thread) throw MigrationException(MigrationDiagnostic.DATABASE_FAILED)
}

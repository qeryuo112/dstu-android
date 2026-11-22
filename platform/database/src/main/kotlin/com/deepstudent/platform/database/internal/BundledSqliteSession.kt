package com.deepstudent.platform.database.internal

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.deepstudent.platform.database.DatabaseDiagnostic
import com.deepstudent.platform.database.DatabaseError
import com.deepstudent.platform.database.DatabaseErrorKind
import java.io.File

/**
 * The only place that constructs the AndroidX bundled driver.
 *
 * Driver and statement types stay internal so callers cannot bypass the probe
 * port or hold a connection owned by another layer.
 */
internal class SqliteConnectionFactory {
    private val driver = BundledSQLiteDriver()

    val coordinate: String = "androidx.sqlite:sqlite-bundled:$ARTIFACT_VERSION"
    val implementationClass: String = driver.javaClass.name
    val hasConnectionPool: Boolean = driver.hasConnectionPool

    fun open(file: File): SqliteSession =
        guardSql("open") {
            SqliteSession(driver.open(file.absolutePath))
        }

    fun openReadOnly(file: File): SqliteSession = guardSql("open-readonly") {
        SqliteSession(driver.open(file.absolutePath, 1)) // SQLITE_OPEN_READONLY; never creates a replacement file.
    }

    fun openMemory(): SqliteSession = guardSql("open-memory") {
        SqliteSession(driver.open(":memory:"))
    }

    private companion object {
        const val ARTIFACT_VERSION = "2.6.2"
    }
}

/** Connection wrapper that closes every prepared statement with `use`. */
internal class SqliteSession internal constructor(
    private val connection: SQLiteConnection,
) : AutoCloseable {

    fun exec(sql: String) {
        guardSql("exec") {
            connection.prepare(sql).use { it.step() }
        }
    }

    fun execBind(sql: String, bind: (SQLiteStatement) -> Unit) {
        guardSql("exec") {
            connection.prepare(sql).use { statement ->
                bind(statement)
                statement.step()
            }
        }
    }

    fun <T> query(
        sql: String,
        bind: (SQLiteStatement) -> Unit = {},
        map: (SQLiteStatement) -> T,
    ): T? = guardSql("query") {
        connection.prepare(sql).use { statement ->
            bind(statement)
            if (statement.step()) map(statement) else null
        }
    }

    fun <T> queryAll(
        sql: String,
        bind: (SQLiteStatement) -> Unit = {},
        map: (SQLiteStatement) -> T,
    ): List<T> = guardSql("query-all") {
        connection.prepare(sql).use { statement ->
            bind(statement)
            buildList {
                while (statement.step()) {
                    add(map(statement))
                }
            }
        }
    }

    fun queryText(sql: String, bind: (SQLiteStatement) -> Unit = {}): String =
        query(sql, bind) { it.getText(0) } ?: missingRow()

    fun queryLong(sql: String, bind: (SQLiteStatement) -> Unit = {}): Long =
        query(sql, bind) { it.getLong(0) } ?: missingRow()

    fun queryTexts(sql: String): List<String> =
        queryAll(sql) { it.getText(0) }

    fun inTransaction(): Boolean = guardSql("in-transaction") { connection.inTransaction() }

    fun prepareRaw(sql: String): SQLiteStatement =
        guardSql("prepare") { connection.prepare(sql) }

    override fun close() {
        connection.close()
    }

    private fun missingRow(): Nothing =
        throw DatabaseFailure(
            DatabaseError(
                kind = DatabaseErrorKind.ASSERTION,
                diagnostic = DatabaseDiagnostic.PROBE_ASSERTION,
                operation = "query",
            ),
        )
}

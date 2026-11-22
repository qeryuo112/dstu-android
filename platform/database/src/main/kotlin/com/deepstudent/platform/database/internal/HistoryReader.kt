package com.deepstudent.platform.database.internal

import com.deepstudent.platform.database.DatabaseErrorKind
import com.deepstudent.platform.database.MigrationDiagnostic
import com.deepstudent.platform.database.MigrationException
import com.deepstudent.platform.database.SharedDatabase

internal data class HistoryEntry(val version: Long, val name: String?, val checksum: String?, val typesValid: Boolean)

/** The desktop baseline has two valid history DDL variants: int4/varchar and integer/text. */
internal class HistoryReader(private val session: SqliteSession, private val database: SharedDatabase) {
    fun read(): List<HistoryEntry> {
        val type = session.query("SELECT type FROM sqlite_master WHERE name='refinery_schema_history'") { it.getText(0) }
        if (type == null) return emptyList()
        if (type != "table") drift()
        return try {
            checkDefinition()
            session.queryAll(
                "SELECT version,name,checksum,typeof(version),typeof(name),typeof(applied_on),typeof(checksum) " +
                    "FROM refinery_schema_history ORDER BY version",
            ) { row ->
                HistoryEntry(
                    if (row.isNull(0)) Long.MIN_VALUE else row.getLong(0),
                    if (row.isNull(1)) null else row.getText(1), if (row.isNull(2)) null else row.getText(2),
                    validTypes(listOf(row.getText(3), row.getText(4), row.getText(5), row.getText(6))),
                )
            }
        } catch (failure: DatabaseFailure) {
            if (failure.error.kind == DatabaseErrorKind.BUSY) throw failure
            drift()
        }
    }

    private fun checkDefinition() {
        val columns = session.queryAll("PRAGMA table_xinfo('refinery_schema_history')") { row ->
            Column(row.getText(1), row.getText(2).uppercase(), row.getLong(3), row.isNull(4), row.getLong(5), row.getLong(6))
        }
        if (columns.size != 4 || columns.map { it.name } != listOf("version", "name", "applied_on", "checksum")) drift()
        columns.forEachIndexed { index, column ->
            if (!validColumn(index, column)) drift()
        }
        if (session.queryLong("SELECT EXISTS(SELECT 1 FROM sqlite_master WHERE type='trigger' AND tbl_name='refinery_schema_history')") != 0L) drift()
    }

    private fun validTypes(types: List<String>): Boolean = types == listOf("integer", "text", "text", "text")

    private fun validColumn(index: Int, column: Column): Boolean {
        val types = if (index == 0) setOf("INT4", "INTEGER") else setOf("VARCHAR(255)", "TEXT")
        return column.type in types && column.notNull == 0L && column.noDefault &&
            column.primaryKey == (if (index == 0) 1L else 0L) && column.hidden == 0L
    }

    private fun drift(): Nothing = throw MigrationException(MigrationDiagnostic.HISTORY_DRIFT, database)
}

private data class Column(
    val name: String, val type: String, val notNull: Long, val noDefault: Boolean, val primaryKey: Long, val hidden: Long,
)

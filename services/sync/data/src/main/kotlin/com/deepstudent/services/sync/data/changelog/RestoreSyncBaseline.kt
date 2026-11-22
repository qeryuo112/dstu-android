package com.deepstudent.services.sync.data.changelog

import com.deepstudent.platform.database.SqlDatabase
import com.deepstudent.platform.database.SqlOperations
import com.deepstudent.platform.database.SqlValue

class RestoreSyncBaseline internal constructor(val clearedChanges: Long, val normalizedRows: Long)

/** Applies only to a migrated, inactive ordinary-restore database. It does not seed a repository. */
fun resetSyncBaselineAfterRestore(candidate: SqlDatabase): RestoreSyncBaseline {
    require(candidate.migrationReport != null)
    return candidate.transaction {
        val normalized = versionedBusinessTables().sumOf { table ->
            execute("UPDATE main.${quoteIdentifier(table)} SET sync_version=local_version " +
                "WHERE local_version IS NOT NULL AND sync_version IS NOT local_version")
            changes()
        }
        clearRestoreTable("__sync_conflicts", optional = true)
        clearRestoreTable("__sync_id_aliases", optional = true)
        clearRestoreTable("__change_log", optional = false)
        RestoreSyncBaseline(changes(), normalized)
    }
}

private fun SqlOperations.versionedBusinessTables() = query("""
    SELECT t.name FROM pragma_table_list t JOIN pragma_table_xinfo(t.name, 'main') c
    WHERE t.schema='main' AND t.type='table' AND t.name NOT GLOB 'sqlite_*' AND t.name NOT GLOB '__*'
        AND c.hidden=0 AND c.name IN ('local_version','sync_version')
    GROUP BY t.name HAVING COUNT(*)=2 ORDER BY t.name
""".trimIndent()) { it.text(0) }.filter { table -> table.matches(Regex("[A-Za-z0-9_]+")) }

private fun SqlOperations.clearRestoreTable(table: String, optional: Boolean) {
    val values = listOf(SqlValue.Text(table))
    val objects = query("SELECT name,type FROM pragma_table_list WHERE schema='main' AND name=? COLLATE NOCASE", values) {
        it.text(0) to it.text(1)
    }
    if (objects.isEmpty() && optional) return
    check(objects == listOf(table to "table"))
    check(query("SELECT 1 FROM sqlite_master WHERE type='trigger' AND tbl_name=? COLLATE NOCASE LIMIT 1", values) { it.long(0) }.isEmpty())
    execute("DELETE FROM main.${quoteIdentifier(table)}")
}

private fun SqlOperations.changes(): Long = query("SELECT changes()") { it.long(0) }.single()

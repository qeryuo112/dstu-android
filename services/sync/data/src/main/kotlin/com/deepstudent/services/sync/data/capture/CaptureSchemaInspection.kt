package com.deepstudent.services.sync.data.capture

import com.deepstudent.platform.database.SharedDatabase
import com.deepstudent.platform.database.SqlReadOperations
import com.deepstudent.platform.database.SqlValue

internal data class CaptureSchemaEvidence(val columns: Map<String, List<String>>, val triggers: Map<String, String>)

internal object CaptureSchemaInspection {
    private val virtual = Regex("(?is)^\\s*CREATE\\s+VIRTUAL\\s+TABLE\\b")
    private val triggerEvent = Regex("(?is)^\\s*CREATE\\s+TRIGGER\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?(?:\\[[^]]+]|`[^`]+`|\"[^\"]+\"|[^\\s]+)\\s+(?:BEFORE|AFTER|INSTEAD\\s+OF)\\s+(INSERT|UPDATE|DELETE)\\b")

    fun inspect(db: SqlReadOperations, owner: SharedDatabase, entries: List<SyncCaptureEntry>, logSchemaHash: String, issues: MutableList<CaptureIssue>): CaptureSchemaEvidence {
        val tables = db.query("SELECT name, sql FROM sqlite_master WHERE type='table'") {
            it.text(0) to if (it.isNull(1)) "" else it.text(1)
        }.toMap()
        val actualTriggers = db.query("SELECT name, tbl_name, sql FROM sqlite_master WHERE type='trigger'") {
            ActualTrigger(it.text(0), it.text(1), if (it.isNull(2)) "" else it.text(2))
        }
        val logDdl = tables["__change_log"]
        if (logDdl == null || sha256(logDdl.toByteArray(Charsets.UTF_8)) != logSchemaHash) {
            issues += CaptureIssue(CaptureDiagnostic.CHANGE_LOG_SCHEMA, owner, "__change_log")
        }
        val columns = entries.mapNotNull { entry ->
            inspectTable(db, entry, tables[entry.table], issues)?.let { entry.table to it }
        }.toMap()
        inspectTriggers(owner, entries, actualTriggers, issues)
        return CaptureSchemaEvidence(columns, actualTriggers.associate { it.name to it.sql })
    }

    private fun inspectTable(db: SqlReadOperations, entry: SyncCaptureEntry, ddl: String?, issues: MutableList<CaptureIssue>): List<String>? {
        if (!entry.present) {
            if (ddl != null) issues += issue(entry, CaptureDiagnostic.TABLE_UNEXPECTED)
            return null
        }
        if (ddl == null) {
            issues += issue(entry, CaptureDiagnostic.TABLE_MISSING)
            return null
        }
        if (virtual.containsMatchIn(ddl) != entry.virtual) {
            issues += issue(entry, CaptureDiagnostic.TABLE_KIND_DRIFT)
            return null
        }
        return if (entry.virtual) null else inspectPrimaryKey(db, entry, issues)
    }

    private fun inspectPrimaryKey(db: SqlReadOperations, entry: SyncCaptureEntry, issues: MutableList<CaptureIssue>): List<String>? {
        val columns = db.tableRows(entry.table)
        val pk = columns.filter { it.pk > 0 }.sortedBy { it.pk }.map { it.name }
        if (pk != entry.sqlitePrimaryKey || !columns.map { it.name }.containsAll(entry.logicalPrimaryKey)) {
            issues += issue(entry, CaptureDiagnostic.PRIMARY_KEY_DRIFT)
            return null
        }
        if (entry.logicalPrimaryKey != pk && !hasUniqueKey(db, entry.table, entry.logicalPrimaryKey)) {
            issues += issue(entry, CaptureDiagnostic.LOGICAL_KEY_NOT_UNIQUE)
            return null
        }
        return columns.map { it.name }
    }

    private fun hasUniqueKey(db: SqlReadOperations, table: String, logicalKey: List<String>): Boolean {
        val indexes = db.query("SELECT name FROM pragma_index_list(?) WHERE \"unique\"=1 AND partial=0", listOf(SqlValue.Text(table))) { it.text(0) }
        return indexes.any { index ->
            val keys = db.query("SELECT name FROM pragma_index_info(?) ORDER BY seqno", listOf(SqlValue.Text(index))) {
                if (it.isNull(0)) null else it.text(0)
            }
            keys == logicalKey
        }
    }

    private fun inspectTriggers(owner: SharedDatabase, entries: List<SyncCaptureEntry>, actual: List<ActualTrigger>, issues: MutableList<CaptureIssue>) {
        val expected = entries.flatMap { entry -> entry.triggers.map { trigger -> trigger.name to (entry to trigger) } }.toMap()
        val actualByName = actual.associateBy { it.name }
        expected.forEach { (name, item) ->
            val (entry, trigger) = item
            val found = actualByName[name]
            when {
                found == null -> issues += issue(entry, CaptureDiagnostic.TRIGGER_MISSING, trigger)
                found.table != entry.table || sha256(found.sql.toByteArray(Charsets.UTF_8)) != trigger.sha256 ->
                    issues += issue(entry, CaptureDiagnostic.TRIGGER_DRIFT, trigger)
            }
        }
        inspectUnexpected(owner, entries, actual.filter { it.name !in expected }, issues)
    }

    private fun inspectUnexpected(owner: SharedDatabase, entries: List<SyncCaptureEntry>, actual: List<ActualTrigger>, issues: MutableList<CaptureIssue>) {
        val entryByName = entries.associateBy { it.table }
        actual.forEach { trigger ->
            val entry = entryByName[trigger.table]
            if (entry != null || trigger.table == "__change_log" || trigger.sql.contains("__change_log", ignoreCase = true)) {
                issues += unexpectedIssue(owner, entry, trigger)
            }
        }
    }

    private fun unexpectedIssue(owner: SharedDatabase, entry: SyncCaptureEntry?, trigger: ActualTrigger): CaptureIssue {
        val event = triggerEvent.find(trigger.sql)?.groupValues?.get(1)?.let(::operation)
        val table = entry?.table ?: "__change_log".takeIf { trigger.table == it }
        val hash = sha256("${owner.id}\u0000${trigger.table}\u0000${trigger.name}".toByteArray(Charsets.UTF_8))
        return CaptureIssue(CaptureDiagnostic.TRIGGER_UNEXPECTED, owner, table, null, event, hash)
    }

    private fun issue(entry: SyncCaptureEntry, diagnostic: CaptureDiagnostic, trigger: FrozenTrigger? = null): CaptureIssue =
        CaptureIssue(diagnostic, entry.database, entry.table, trigger?.name, trigger?.operation)

    private data class ActualTrigger(val name: String, val table: String, val sql: String)
}

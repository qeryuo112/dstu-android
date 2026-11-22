package com.deepstudent.services.sync.data.capture

import com.deepstudent.platform.database.SharedDatabase
import com.deepstudent.platform.database.SqlDatabase
import com.deepstudent.platform.database.SqlReadOperations
import com.deepstudent.platform.database.SqlValue
import java.util.concurrent.CancellationException

enum class CaptureDiagnostic {
    DATABASE_IDENTITY, VERSION_DRIFT, CHANGE_LOG_SCHEMA, TABLE_MISSING, TABLE_UNEXPECTED, TABLE_KIND_DRIFT,
    PRIMARY_KEY_DRIFT, LOGICAL_KEY_NOT_UNIQUE, TRIGGER_MISSING, TRIGGER_DRIFT,
    TRIGGER_UNEXPECTED, QUERY_FAILED, OPERATION_COMPILE_FAILED, SCOPE_INVALID,
}

/** Names are included only when they came from the verified frozen contract. */
data class CaptureIssue(
    val diagnostic: CaptureDiagnostic,
    val database: SharedDatabase,
    val table: String? = null,
    val trigger: String? = null,
    val operation: CaptureOperation? = null,
    val objectHash: String? = null,
)

class SyncCaptureException(issue: CaptureIssue) : IllegalStateException("sync capture preflight: ${issue.diagnostic.name}") {
    val diagnostic = issue.diagnostic
    val database = issue.database
    val table = issue.table
    val trigger = issue.trigger
    val operation = issue.operation
    val objectHash = issue.objectHash
}

/** Only vetted registry identifiers, bounded counts and fixed diagnostic codes reach the callback. */
fun interface CaptureLog {
    fun onReport(report: CaptureReport)
    companion object { val NONE: CaptureLog = CaptureLog { } }
}

object SyncCapturePreflight {
    /** Introspection runs under one fixed WAL snapshot and never writes to the supplied database. */
    fun validate(database: SharedDatabase, db: SqlDatabase, registry: SyncCaptureRegistry, log: CaptureLog = CaptureLog.NONE): CaptureReport {
        val entries = registry.entries.filter { it.database == database }
        val issues = mutableListOf<CaptureIssue>()
        val operations = OperationCounts()
        val inspected = if (sameOwner(db, database, registry)) inspectSafely(db, database, registry, entries, issues, operations) else {
            issues += CaptureIssue(CaptureDiagnostic.DATABASE_IDENTITY, database)
            false
        }
        return report(database, entries, inspected, issues, operations).also { result -> runCatching { log.onReport(result) } }
    }

    private fun sameOwner(db: SqlDatabase, owner: SharedDatabase, registry: SyncCaptureRegistry): Boolean =
        db.migrationReport?.let { it.database == owner && it.toVersion == registry.versions[owner] } == true

    private fun inspectSafely(
        db: SqlDatabase, owner: SharedDatabase, registry: SyncCaptureRegistry,
        entries: List<SyncCaptureEntry>, issues: MutableList<CaptureIssue>, operations: OperationCounts,
    ): Boolean = try {
        db.readSnapshot {
            val versions = query("SELECT MAX(version) FROM refinery_schema_history") { if (it.isNull(0)) null else it.long(0) }
            if (versions.singleOrNull() != registry.versions.getValue(owner).toLong()) {
                issues += CaptureIssue(CaptureDiagnostic.VERSION_DRIFT, owner)
                false
            } else {
                val schema = CaptureSchemaInspection.inspect(this, owner, entries, registry.changeLogSchemas.getValue(owner), issues)
                if (issues.isEmpty()) CaptureOperationInspection.inspect(this, entries, schema, issues, operations)
                true
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        issues += CaptureIssue(CaptureDiagnostic.QUERY_FAILED, owner)
        false
    }

    private fun report(
        owner: SharedDatabase, entries: List<SyncCaptureEntry>, inspected: Boolean,
        issues: List<CaptureIssue>, operations: OperationCounts,
    ): CaptureReport {
        val checked = if (inspected) entries else emptyList()
        val metrics = CaptureMetrics(checked.size, checked.count { it.category == SyncCategory.RowSync },
            checked.sumOf { it.triggers.size }, operations.attempted, operations.succeeded)
        return CaptureReport(owner, metrics, issues, entries.filter { it.category == SyncCategory.RowSync }.map { it.table }.toSet())
    }

}

internal fun SqlReadOperations.tableRows(name: String): List<Column> = query(
    "SELECT name, pk FROM pragma_table_info(?) ORDER BY cid", listOf(SqlValue.Text(name)),
) { Column(it.text(0), it.long(1).toInt()) }
internal data class Column(val name: String, val pk: Int)

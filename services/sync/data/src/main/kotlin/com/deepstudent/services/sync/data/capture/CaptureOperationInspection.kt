package com.deepstudent.services.sync.data.capture

import com.deepstudent.platform.database.DatabaseDiagnostic
import com.deepstudent.platform.database.SqlReadOperations
import java.util.concurrent.CancellationException

internal class OperationCounts(var attempted: Int = 0, var succeeded: Int = 0)

/** Compile the full SQLite trigger programs on the same read-only WAL snapshot. */
internal object CaptureOperationInspection {
    private val safeCompileFailures = setOf(
        DatabaseDiagnostic.SQL_NO_SUCH_TABLE.fixedMessage,
        DatabaseDiagnostic.SQL_NO_SUCH_COLUMN.fixedMessage,
        DatabaseDiagnostic.SQL_NO_SUCH_FUNCTION.fixedMessage,
        DatabaseDiagnostic.SQL_SYNTAX_ERROR.fixedMessage,
        DatabaseDiagnostic.SQL_REJECTED.fixedMessage,
    )

    fun inspect(
        db: SqlReadOperations, entries: List<SyncCaptureEntry>, schema: CaptureSchemaEvidence,
        issues: MutableList<CaptureIssue>, counts: OperationCounts,
    ) {
        entries.filter { it.category == SyncCategory.RowSync }.forEach { entry ->
            val columns = checkNotNull(schema.columns[entry.table])
            CaptureOperation.entries.forEach { op -> probe(db, entry, op, columns, schema.triggers, issues, counts) }
        }
    }

    private fun probe(
        db: SqlReadOperations, entry: SyncCaptureEntry, op: CaptureOperation, columns: List<String>,
        triggers: Map<String, String>, issues: MutableList<CaptureIssue>, counts: OperationCounts,
    ) {
        counts.attempted++
        try {
            db.query(statement(entry, op, columns)) { Unit }
            counts.succeeded++
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e.message !in safeCompileFailures) throw e
            val trigger = CaptureTriggerFaultLocator.locate(entry, op, triggers, e.message)
            issues += CaptureIssue(CaptureDiagnostic.OPERATION_COMPILE_FAILED, entry.database, entry.table, trigger, op)
        }
    }

    private fun statement(entry: SyncCaptureEntry, op: CaptureOperation, columns: List<String>): String {
        val table = quote(entry.table)
        val action = when (op) {
            CaptureOperation.INSERT -> "INSERT INTO $table (${quote(entry.logicalPrimaryKey.first())}) SELECT NULL WHERE 0"
            CaptureOperation.UPDATE -> "UPDATE $table SET ${columns.joinToString(",") { "${quote(it)}=${quote(it)}" }} WHERE 0"
            CaptureOperation.DELETE -> "DELETE FROM $table WHERE 0"
        }
        return "EXPLAIN QUERY PLAN $action"
    }

    private fun quote(name: String): String = "\"${name.replace("\"", "\"\"")}\""
}

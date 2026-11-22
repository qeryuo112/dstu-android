package com.deepstudent.services.sync.data.capture

import com.deepstudent.platform.database.SharedDatabase
import java.util.Collections

/** Counts refer to actual checks; EXPLAIN only compiles and never executes the represented writes. */
internal data class CaptureMetrics(
    val entries: Int = 0,
    val rowSync: Int = 0,
    val triggers: Int = 0,
    val operations: Int = 0,
    val operationsSucceeded: Int = 0,
)

class CaptureReport internal constructor(
    val database: SharedDatabase,
    private val metrics: CaptureMetrics,
    issues: List<CaptureIssue>,
    rowSyncTables: Set<String>,
) {
    private val allowedTables = Collections.unmodifiableSet(rowSyncTables.toSet())
    val issues: List<CaptureIssue> = Collections.unmodifiableList(issues.toList())
    val structureIssues: List<CaptureIssue> = Collections.unmodifiableList(issues.filterNot { it.isOperationIssue })
    val operationIssues: List<CaptureIssue> = Collections.unmodifiableList(issues.filter { it.isOperationIssue })
    val entriesChecked: Int get() = metrics.entries
    val rowSyncChecked: Int get() = metrics.rowSync
    val triggersChecked: Int get() = metrics.triggers
    val operationChecks: Int get() = metrics.operations
    val operationSucceeded: Int get() = metrics.operationsSucceeded
    val valid: Boolean get() = issues.isEmpty()

    /** Requires every structure and every operation, independent of which tables a caller consumes. */
    fun requireValid(): CaptureReport {
        issues.firstOrNull()?.let { throw SyncCaptureException(it) }
        return this
    }

    /** Structure always blocks; only a compile issue belonging to an unconsumed table may be scoped out. */
    fun requireReady(tables: Set<String>): CaptureReport {
        structureIssues.firstOrNull()?.let { throw SyncCaptureException(it) }
        if (tables.isEmpty() || !allowedTables.containsAll(tables)) {
            throw SyncCaptureException(CaptureIssue(CaptureDiagnostic.SCOPE_INVALID, database))
        }
        operationIssues.firstOrNull { it.table == null || it.table in tables }?.let { throw SyncCaptureException(it) }
        return this
    }
}

internal val CaptureIssue.isOperationIssue: Boolean get() = diagnostic == CaptureDiagnostic.OPERATION_COMPILE_FAILED

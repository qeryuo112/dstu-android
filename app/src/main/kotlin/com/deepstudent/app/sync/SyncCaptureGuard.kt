package com.deepstudent.app.sync

import android.util.Log
import com.deepstudent.platform.database.SharedDatabase
import com.deepstudent.platform.database.SqlDatabase
import com.deepstudent.services.sync.api.SyncError
import com.deepstudent.services.sync.backend.SyncFailure
import com.deepstudent.services.sync.data.capture.CaptureLog
import com.deepstudent.services.sync.data.capture.SyncCaptureException
import com.deepstudent.services.sync.data.capture.SyncCapturePreflight
import com.deepstudent.services.sync.data.capture.SyncCaptureRegistry
import com.deepstudent.services.sync.data.capture.SyncCaptureSourceException

/** VFS is checked before remote access; the existing Mistakes route remains lazy. */
internal class SyncCaptureGuard(
    private val captureLog: CaptureLog,
    private val diagnostic: (String) -> Unit,
    loadRegistry: () -> SyncCaptureRegistry,
) {
    val registry by lazy(loadRegistry)

    fun requireReady(database: SharedDatabase, db: SqlDatabase, tables: Set<String>) {
        try {
            SyncCapturePreflight.validate(database, db, registry, captureLog).requireReady(tables)
            diagnostic("owner=sync/capture database=${database.id} stage=active_scope status=passed tables=${tables.size}")
        } catch (failure: SyncCaptureSourceException) {
            diagnostic("owner=sync/capture database=${database.id} stage=registry status=failed diagnostic=${failure.diagnostic}")
            throw SyncFailure(SyncError.STORAGE)
        } catch (_: SyncCaptureException) {
            throw SyncFailure(SyncError.STORAGE)
        }
    }
}

private const val TAG = "DstuSyncCapture"
internal fun androidCaptureDiagnostic(message: String) { Log.i(TAG, message) }
internal val androidCaptureLog = CaptureLog { report ->
    val counts = "entries=${report.entriesChecked} rowSync=${report.rowSyncChecked} triggers=${report.triggersChecked} " +
        "compiledOperations=${report.operationSucceeded}/${report.operationChecks}"
    Log.i(TAG, "owner=sync/capture database=${report.database.id} stage=database_preflight status=${if (report.valid) "passed" else "failed"} $counts")
    for (issue in report.issues.take(20)) {
        Log.w(TAG, "owner=sync/capture database=${issue.database.id} diagnostic=${issue.diagnostic} " +
            "table=${issue.table} trigger=${issue.trigger} operation=${issue.operation} objectHash=${issue.objectHash}")
    }
}

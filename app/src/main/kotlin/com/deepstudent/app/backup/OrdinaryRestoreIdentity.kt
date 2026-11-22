package com.deepstudent.app.backup

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.deepstudent.app.security.MainSecurityResources
import com.deepstudent.platform.database.SharedDatabase
import com.deepstudent.platform.security.SecurityPorts
import com.deepstudent.services.backup.data.BackupIdentityLog
import com.deepstudent.services.sync.data.LocalSyncStore
import com.deepstudent.services.sync.data.changelog.RestoreSyncBaseline
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS

/** A local identity change after the slot commit is restartable using the same durable operation. */
internal class OrdinaryRestoreIdentity(
    private val context: Context,
    private val openSecurity: () -> SecurityPorts = { MainSecurityResources.open(context) },
) {
    fun finish(operationId: String): Boolean {
        val started = SystemClock.elapsedRealtime()
        val sync = context.noBackupFilesDir.canonicalFile.resolve("sync")
        log(operationId, "writer", "started", started)
        return try {
            val rotated = if (Files.notExists(sync.toPath(), NOFOLLOW_LINKS)) false else openSecurity().use { ports ->
                LocalSyncStore(sync, ports.credentials).rotateWriterForRestore(operationId)
            }
            log(operationId, "writer", if (rotated) "rotated" else "never_bound", started)
            rotated
        } catch (error: Exception) {
            log(operationId, "writer", "failed", started, "SYNC_IDENTITY_IO")
            throw error
        }
    }

    private fun log(id: String, phase: String, state: String, started: Long, code: String = "none") {
        Log.i("DstuRestoreIdentity", "event=backup.identity owner=app.backup correlationId=$id phase=$phase " +
            "state=$state elapsedMs=${SystemClock.elapsedRealtime() - started} code=$code")
    }
}

internal fun androidIdentityLog(operationId: String) = BackupIdentityLog { event ->
    Log.i("DstuRestoreIdentity", "event=backup.identity owner=${event.owner} correlationId=$operationId " +
        "phase=${event.phase} state=${event.status} elapsedMs=${event.elapsedMillis} code=${event.diagnostic ?: "none"}")
}

internal fun androidBaselineLog(operationId: String): (SharedDatabase, RestoreSyncBaseline?, Long) -> Unit = { database, result, elapsed ->
    Log.i("DstuRestoreIdentity", "event=backup.sync_baseline owner=sync/changelog correlationId=$operationId " +
        "database=${database.id} state=${if (result == null) "failed" else "rebuilt"} elapsedMs=$elapsed " +
        if (result == null) "code=SYNC_BASELINE_FAILED" else "cleared=${result.clearedChanges} normalized=${result.normalizedRows}")
}

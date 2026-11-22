package com.deepstudent.app.backup

import com.deepstudent.platform.database.MigrationSource
import com.deepstudent.platform.database.SharedDatabase
import com.deepstudent.services.backup.api.RestorePlan
import com.deepstudent.services.backup.data.BackupIdentityLog
import com.deepstudent.services.backup.data.BackupIdentityStore
import com.deepstudent.services.sync.data.changelog.RestoreSyncBaseline
import com.deepstudent.services.sync.data.changelog.resetSyncBaselineAfterRestore

internal fun ordinaryRestoreVerifier(
    source: MigrationSource,
    plan: RestorePlan,
    legacy: LegacyRestoreChecks? = null,
    identityLog: BackupIdentityLog = BackupIdentityLog.NONE,
    baselineLog: (SharedDatabase, RestoreSyncBaseline?, Long) -> Unit = { _, _, _ -> },
) = GovernedRestoreVerifier(source, plan, legacy) { id, database ->
    val started = System.nanoTime()
    val result = try {
        resetSyncBaselineAfterRestore(database)
    } catch (failure: Exception) {
        baselineLog(id, null, (System.nanoTime() - started) / 1_000_000)
        throw failure
    }
    baselineLog(id, result, (System.nanoTime() - started) / 1_000_000)
    if (id == SharedDatabase.VFS) BackupIdentityStore(database, identityLog).resetForOrdinaryRestore()
}

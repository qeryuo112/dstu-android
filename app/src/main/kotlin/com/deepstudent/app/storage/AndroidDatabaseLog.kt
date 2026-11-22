package com.deepstudent.app.storage

import android.util.Log
import com.deepstudent.platform.database.DatabaseLog
import com.deepstudent.platform.database.DatabasePhase
import com.deepstudent.platform.database.DatabaseStatus
import com.deepstudent.platform.database.MigrationLog

/** Fixed fields only; avoid per-statement success logs in production. */
internal val AndroidDatabaseLog = DatabaseLog { event ->
    if (event.status != DatabaseStatus.SUCCESS || event.phase == DatabasePhase.OPEN || event.phase == DatabasePhase.CLOSE) {
        Log.i("DstuDatabase", "owner=${event.owner} phase=${event.phase} status=${event.status} " +
            "wait=${event.wait} elapsedMs=${event.elapsedMillis}")
    }
}

internal val AndroidMigrationLog = MigrationLog { event ->
    Log.i("DstuStorage", "owner=${event.owner} database=${event.database.id} correlationId=${event.correlationId} " +
        "phase=${event.phase} state=${event.status} fromVersion=${event.fromVersion} version=${event.version} " +
        "appliedCount=${event.appliedCount} fingerprint=${event.fingerprint} diagnostic=${event.diagnostic} " +
        "objectType=${event.objectType} objectName=${event.objectName} " +
        "stage=${event.details.status?.stage} committedVersion=${event.details.status?.committedVersion} " +
        "targetVersion=${event.details.status?.targetVersion} failureVersion=${event.details.status?.failure?.version} " +
        "elapsedMs=${event.durationMillis}")
}

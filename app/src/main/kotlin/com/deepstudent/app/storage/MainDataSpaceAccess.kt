package com.deepstudent.app.storage

import android.content.Context
import android.util.Log
import com.deepstudent.platform.database.BundledMigrationSource
import com.deepstudent.platform.database.MigrationMaintenance
import com.deepstudent.platform.database.SharedDatabase
import com.deepstudent.platform.storage.DataSpaceAudit
import com.deepstudent.platform.storage.FileDataSpace
import java.io.File

/** The main process pins one slot until every database and file consumer has stopped. */
internal class MainDataSpaceAccess(private val context: Context) : AutoCloseable {
    private val lease = lazy {
        FileDataSpace(context.filesDir, audit = AndroidDataSpaceAudit).acquireActive()
    }
    val activeRoot: File get() = lease.value.root
    val migration: MigrationMaintenance by lazy {
        MigrationMaintenance(
            activeRoot.resolve("databases/vfs.db"), SharedDatabase.VFS, BundledMigrationSource(context),
            log = AndroidDatabaseLog, migrationLog = AndroidMigrationLog,
        )
    }

    override fun close() {
        if (lease.isInitialized()) lease.value.close()
    }
}

internal val AndroidDataSpaceAudit = DataSpaceAudit { event ->
    Log.i("DstuDataSpace", "event=storage.dataspace owner=${event.owner} correlationId=${event.correlationId} " +
        "phase=${event.phase} state=${event.outcome} elapsedMs=${event.elapsedMillis} code=${event.error ?: "none"}")
}

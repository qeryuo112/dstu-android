package com.deepstudent.platform.database

import java.io.File
import java.util.concurrent.CancellationException

/** Stable, non-sensitive migration state. Null versions mean the value could not be verified. */
public data class MigrationStatus(
    val database: SharedDatabase,
    val state: MigrationState = MigrationState.IDLE,
    val stage: MigrationStage = MigrationStage.REGISTRY,
    val committedVersion: Int? = null,
    val targetVersion: Int? = null,
    val failure: MigrationFailure? = null,
)

public data class MigrationFailure(
    val database: SharedDatabase,
    val version: Int?,
    val diagnostic: MigrationDiagnostic,
    val objectType: String? = null,
    val objectName: String? = null,
)

public enum class MigrationState { IDLE, RUNNING, FAILED, READY }
public enum class MigrationStage { REGISTRY, SOURCE, OPEN, INSPECT, MIGRATION, GOVERNANCE, WAL, READY }

/** Deliberately has no underlying cause: a driver or source may include secrets in its message. */
public class MigrationMaintenanceException(public val status: MigrationStatus) :
    IllegalStateException("database migration requires maintenance")

/**
 * One owner for a startup database. Opens and retries are serialized and both go through
 * DatabaseRegistry.openShared: retry cannot skip, erase, or replay committed migrations.
 * Query never opens the file. Unknown versions are not inferred from an invalid history.
 */
public class MigrationMaintenance(
    private val file: File,
    private val database: SharedDatabase,
    private val source: MigrationSource,
    private val profile: DatabaseProfile = DatabaseProfile.shared(database),
    private val log: DatabaseLog = DatabaseLog.NONE,
    private val migrationLog: MigrationLog = MigrationLog.NONE,
) {
    private val lock = Any()
    @Volatile private var current = MigrationStatus(database)

    public fun query(): MigrationStatus = current

    public fun open(): SqlDatabase = synchronized(lock) {
        // An ordinary startup caller must not silently retry a failure seen by another caller.
        if (current.state == MigrationState.FAILED) throw MigrationMaintenanceException(current)
        openLocked()
    }

    /** Run the same governed open once, close its lease, and return the new diagnostic. */
    public fun retry(): MigrationStatus = synchronized(lock) {
        try {
            openLocked().use { }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // openLocked has already published a safe diagnostic; close failures need one too.
            if (current.state != MigrationState.FAILED) current = registryFailure()
        }
        current
    }

    private fun openLocked(): SqlDatabase {
        current = MigrationStatus(database, MigrationState.RUNNING, MigrationStage.REGISTRY)
        var completed: MigrationStatus? = null
        val recorder = MigrationLog { event ->
            event.details.status?.let { completed = it }
            migrationLog.onEvent(event)
        }
        try {
            val lease = DatabaseRegistry.openShared(file, database, source, profile, log, recorder)
            current = checkNotNull(completed)
            return lease
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            current = completed?.takeIf { it.state == MigrationState.FAILED } ?: registryFailure()
            throw MigrationMaintenanceException(current)
        }
    }

    private fun registryFailure(): MigrationStatus = current.copy(
        state = MigrationState.FAILED, stage = MigrationStage.REGISTRY,
        failure = MigrationFailure(database, null, MigrationDiagnostic.DATABASE_FAILED),
    )
}

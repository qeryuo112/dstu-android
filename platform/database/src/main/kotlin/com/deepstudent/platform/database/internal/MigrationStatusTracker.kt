package com.deepstudent.platform.database.internal

import com.deepstudent.platform.database.MigrationException
import com.deepstudent.platform.database.MigrationFailure
import com.deepstudent.platform.database.MigrationReport
import com.deepstudent.platform.database.MigrationStage
import com.deepstudent.platform.database.MigrationState
import com.deepstudent.platform.database.MigrationStatus
import com.deepstudent.platform.database.SharedDatabase

/** Only trusted migration metadata and fixed enum stages enter a public snapshot. */
internal class MigrationStatusTracker(private val database: SharedDatabase) {
    private var value = MigrationStatus(database, MigrationState.RUNNING, MigrationStage.SOURCE)
    private var applyingVersion: Int? = null

    fun target(version: Int) { value = value.copy(targetVersion = version) }
    fun stage(stage: MigrationStage) { value = value.copy(stage = stage) }
    fun inspected(version: Int) { value = value.copy(committedVersion = version) }
    fun applying(version: Int) { applyingVersion = version; stage(MigrationStage.MIGRATION) }
    fun committed(version: Int) { value = value.copy(committedVersion = version); applyingVersion = null }

    fun failed(failure: MigrationException): MigrationStatus = value.copy(
        state = MigrationState.FAILED,
        failure = MigrationFailure(
            failure.database ?: database, failure.version ?: applyingVersion, failure.diagnostic,
            failure.objectType, failure.objectName,
        ),
    )

    fun ready(report: MigrationReport): MigrationStatus = value.copy(
        state = MigrationState.READY, stage = MigrationStage.READY,
        committedVersion = report.toVersion, targetVersion = report.toVersion,
    )
}

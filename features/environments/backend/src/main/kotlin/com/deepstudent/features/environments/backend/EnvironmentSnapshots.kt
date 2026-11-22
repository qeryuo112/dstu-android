package com.deepstudent.features.environments.backend

import com.deepstudent.features.environments.api.EnvironmentFailure
import com.deepstudent.features.environments.api.EnvironmentFailureCode
import com.deepstudent.features.environments.api.EnvironmentPhase
import com.deepstudent.features.environments.api.EnvironmentProgress
import com.deepstudent.features.environments.api.EnvironmentRuntimeState
import com.deepstudent.features.environments.api.EnvironmentSnapshot
import com.deepstudent.features.environments.api.EnvironmentTaskId

internal fun restoredEnvironmentSnapshot(journal: EnvironmentJournal?): EnvironmentSnapshot {
    if (journal == null) return EnvironmentSnapshot(runtime = EnvironmentRuntimeState(
        recipes = PackageRecipeCatalog.states(emptyMap(), null, false, false, available = false),
    ))
    val checked = journal.copy(failure = restoredFailure(journal))
    val phase = restoredPhase(checked)
    return environmentSnapshot(checked, phase, EnvironmentProgress(stage = checkpointStage(journal.checkpoint)), null)
}

private fun restoredFailure(journal: EnvironmentJournal): EnvironmentFailure? {
    journal.failure?.let { return it }
    if (journal.checkpoint != EnvironmentCheckpoint.DONE || journal.pendingRecipeId != null) {
        return EnvironmentFailure(EnvironmentFailureCode.INTERRUPTED,
            EnvironmentFailureMessages.forCode(EnvironmentFailureCode.INTERRUPTED), true)
    }
    if (journal.activeGeneration == null) {
        return EnvironmentFailure(EnvironmentFailureCode.CORRUPT_STATE,
            EnvironmentFailureMessages.forCode(EnvironmentFailureCode.CORRUPT_STATE), false)
    }
    return null
}

private fun restoredPhase(journal: EnvironmentJournal): EnvironmentPhase = when {
    journal.failure?.code == EnvironmentFailureCode.CANCELLED -> EnvironmentPhase.CANCELLED
    journal.failure != null -> EnvironmentPhase.FAILED
    else -> EnvironmentPhase.READY
}

internal fun environmentSnapshot(
    journal: EnvironmentJournal,
    phase: EnvironmentPhase,
    progress: EnvironmentProgress,
    taskId: EnvironmentTaskId?,
): EnvironmentSnapshot = EnvironmentSnapshot(
    phase = phase,
    rootfsId = journal.rootfsId,
    rootfsSha256 = journal.rootfsSha256,
    activeGeneration = journal.activeGeneration,
    runtime = EnvironmentRuntimeState(
        taskId = taskId,
        progress = progress,
        recipes = PackageRecipeCatalog.states(
            journal.receipts, journal.pendingRecipeId,
            installing = phase == EnvironmentPhase.INSTALLING_PACKAGE,
            failed = journal.failure != null,
            available = journal.activeGeneration != null && journal.checkpoint == EnvironmentCheckpoint.DONE,
        ),
        lastReceipt = journal.receipts.values.maxByOrNull { it.installedAt },
        failure = journal.failure,
        recoverable = journal.failure?.recoverable == true,
    ),
)

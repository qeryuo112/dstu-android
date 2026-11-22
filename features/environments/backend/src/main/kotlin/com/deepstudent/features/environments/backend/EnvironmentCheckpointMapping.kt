package com.deepstudent.features.environments.backend

import com.deepstudent.features.environments.api.EnvironmentFailureCode
import com.deepstudent.features.environments.api.EnvironmentPhase
import com.deepstudent.features.environments.api.EnvironmentStage
import java.io.IOException

internal fun checkpointPhase(checkpoint: EnvironmentCheckpoint): EnvironmentPhase = when (checkpoint) {
    EnvironmentCheckpoint.VERIFY -> EnvironmentPhase.VERIFYING
    EnvironmentCheckpoint.EXTRACT -> EnvironmentPhase.EXTRACTING
    EnvironmentCheckpoint.HEALTH -> EnvironmentPhase.HEALTH_CHECKING
    EnvironmentCheckpoint.ACTIVATE -> EnvironmentPhase.ACTIVATING
    EnvironmentCheckpoint.DONE -> EnvironmentPhase.READY
}

internal fun checkpointStage(checkpoint: EnvironmentCheckpoint): EnvironmentStage = when (checkpoint) {
    EnvironmentCheckpoint.VERIFY -> EnvironmentStage.ARCHIVE_VERIFY
    EnvironmentCheckpoint.EXTRACT -> EnvironmentStage.EXTRACT
    EnvironmentCheckpoint.HEALTH -> EnvironmentStage.HEALTH_CHECK
    EnvironmentCheckpoint.ACTIVATE -> EnvironmentStage.ACTIVATE
    EnvironmentCheckpoint.DONE -> EnvironmentStage.COMPLETE
}

internal fun environmentFailureCode(failure: Exception): EnvironmentFailureCode =
    if (failure is IOException) EnvironmentFailureCode.INVALID_ARCHIVE else EnvironmentFailureCode.UNKNOWN

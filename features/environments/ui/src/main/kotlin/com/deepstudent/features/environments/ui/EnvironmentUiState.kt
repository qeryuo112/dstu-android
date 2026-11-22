package com.deepstudent.features.environments.ui

import com.deepstudent.features.environments.api.EnvironmentFailure
import com.deepstudent.features.environments.api.EnvironmentPhase
import com.deepstudent.features.environments.api.EnvironmentSnapshot

data class EnvironmentUiState(
    val snapshot: EnvironmentSnapshot = EnvironmentSnapshot(),
    val busy: Boolean = snapshot.phase.isBusy(),
    val actionFailure: EnvironmentFailure? = null,
    val downloadAvailable: Boolean = false,
) {
    val failure: EnvironmentFailure? get() = actionFailure ?: snapshot.failure
}

data class EnvironmentActions(
    val installRootfs: () -> Unit,
    val downloadRootfs: () -> Unit,
    val retry: () -> Unit,
    val cancel: () -> Unit,
    val installRecipe: (String) -> Unit,
    val dismissFailure: () -> Unit,
    val close: () -> Unit,
)

internal fun EnvironmentPhase.isBusy(): Boolean = when (this) {
    EnvironmentPhase.VERIFYING,
    EnvironmentPhase.EXTRACTING,
    EnvironmentPhase.HEALTH_CHECKING,
    EnvironmentPhase.ACTIVATING,
    EnvironmentPhase.INSTALLING_PACKAGE,
    -> true
    else -> false
}

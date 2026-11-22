package com.deepstudent.features.environments.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import com.deepstudent.features.environments.api.EnvironmentManager
import com.deepstudent.features.environments.api.RootfsArchiveSource

@Composable
fun EnvironmentsRoute(
    manager: EnvironmentManager,
    archivePicker: suspend () -> RootfsArchiveSource?,
    onClosePage: () -> Unit,
    modifier: Modifier = Modifier,
    downloadSource: RootfsArchiveSource? = null,
) {
    val scope = rememberCoroutineScope()
    val currentPicker by rememberUpdatedState(archivePicker)
    val presenter = remember(manager, scope, downloadSource) {
        EnvironmentsPresenter(manager, scope, downloadSource) { currentPicker() }
    }
    DisposableEffect(presenter) {
        onDispose(presenter::stop)
    }
    val state by presenter.state.collectAsState()
    EnvironmentsScreen(
        state = state,
        actions = presenter.actions.copy(close = onClosePage),
        modifier = modifier,
    )
}

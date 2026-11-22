package com.deepstudent.features.workspace.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import com.deepstudent.features.workspace.api.OpenPanel
import com.deepstudent.features.workspace.api.PanelInstance
import com.deepstudent.features.workspace.api.Workspace
import com.deepstudent.features.workspace.api.WorkspaceMode
import com.deepstudent.features.workspace.api.WorkspaceStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun WorkspaceRoute(
    workspace: Workspace,
    launchers: List<PanelLauncher>,
    modifier: Modifier = Modifier,
    content: @Composable (PanelInstance, Boolean) -> Unit,
) {
    val state by workspace.state.collectAsState()
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val tablet = LocalConfiguration.current.screenWidthDp >= 840
    LaunchedEffect(workspace, tablet) {
        workspace.restore()
        if (workspace.state.value.status == WorkspaceStatus.Ready) {
            workspace.setMode(if (tablet) WorkspaceMode.TabletPanels else WorkspaceMode.PhoneStack)
        }
    }
    val dispatch: (WorkspaceAction) -> Unit = { action ->
        scope.launch {
            try { failed = false; performAction(workspace, action) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failed = true }
        }
    }
    BackHandler(enabled = state.pendingClose != null) { workspace.cancelClose() }
    WorkspaceScreen(state, launchers, dispatch, failed, modifier, content)
}

private suspend fun performAction(workspace: Workspace, action: WorkspaceAction) {
    when (action) {
        is WorkspaceAction.Open -> {
            val existing = workspace.state.value.layout.panels.firstOrNull { it.key == action.key }
            if (existing == null) workspace.open(OpenPanel(action.key)) else workspace.focus(existing.id)
        }
        is WorkspaceAction.Focus -> workspace.focus(action.id)
        is WorkspaceAction.Close -> workspace.close(action.id)
        is WorkspaceAction.Decide -> workspace.resolveClose(action.decision)
        is WorkspaceAction.AcknowledgeSave -> workspace.acknowledgeSaveUncertain(action.id)
        WorkspaceAction.Retry -> workspace.restore()
    }
}

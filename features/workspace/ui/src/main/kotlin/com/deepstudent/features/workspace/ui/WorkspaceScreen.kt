package com.deepstudent.features.workspace.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.deepstudent.design.DstuTheme
import com.deepstudent.features.workspace.api.PanelInstance
import com.deepstudent.features.workspace.api.WorkspaceState
import com.deepstudent.features.workspace.api.WorkspaceStatus

@Composable
fun WorkspaceScreen(
    state: WorkspaceState,
    launchers: List<PanelLauncher>,
    dispatch: (WorkspaceAction) -> Unit,
    failed: Boolean = false,
    modifier: Modifier = Modifier,
    content: @Composable (PanelInstance, Boolean) -> Unit,
) {
    CompositionLocalProvider(LocalContentColor provides DstuTheme.colors.foreground) {
        Column(modifier.fillMaxSize().testTag("native-workspace")) {
            WorkspaceToolbar(state, launchers, dispatch)
            state.feedback?.let { Text(it.message, Modifier.fillMaxWidth().testTag("workspace-feedback")) }
            if (failed) Text("操作未完成，草稿已保留", Modifier.testTag("workspace-command-error"))
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                when (state.status) {
                    WorkspaceStatus.Loading -> CircularProgressIndicator()
                    WorkspaceStatus.Error -> TextButton({ dispatch(WorkspaceAction.Retry) }) { Text("重试恢复工作区") }
                    WorkspaceStatus.Ready -> WorkspacePanels(state.layout, dispatch, content)
                }
            }
            WorkspaceCloseDialog(state, dispatch)
        }
    }
}

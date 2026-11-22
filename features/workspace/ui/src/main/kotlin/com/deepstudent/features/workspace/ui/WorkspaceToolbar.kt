package com.deepstudent.features.workspace.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.unit.dp
import com.deepstudent.design.DstuTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.deepstudent.features.workspace.api.WorkspaceState
import com.deepstudent.features.workspace.api.WorkspaceStatus

@Composable
internal fun WorkspaceToolbar(state: WorkspaceState, launchers: List<PanelLauncher>, dispatch: (WorkspaceAction) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
        launchers.forEach { launcher ->
            TextButton(onClick = { dispatch(WorkspaceAction.Open(launcher.key)) },
                enabled = state.status == WorkspaceStatus.Ready,
                modifier = Modifier.testTag("workspace-open-${launcher.key.value}")) { Text(launcher.title, maxLines = 1) }
        }
    }
    HorizontalDivider()
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
        state.layout.panels.forEach { panel ->
            TextButton(onClick = { dispatch(WorkspaceAction.Focus(panel.id)) },
                colors = ButtonDefaults.textButtonColors(contentColor =
                    if (state.layout.activePanelId == panel.id) DstuTheme.colors.primary else DstuTheme.colors.foreground),
                modifier = Modifier.widthIn(max = 220.dp).semantics { selected = state.layout.activePanelId == panel.id }
                    .testTag("workspace-tab-${panel.key.value}")) {
                Text(panel.title + if (panel.dirty) " *" else "", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = { dispatch(WorkspaceAction.Close(panel.id)) }, enabled = state.canClose,
                modifier = Modifier.testTag("workspace-close-${panel.key.value}")) {
                Icon(Icons.Default.Close, "关闭${panel.title}")
            }
        }
    }
    HorizontalDivider()
}

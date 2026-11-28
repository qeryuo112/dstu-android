package com.deepstudent.app.workspace

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.deepstudent.app.BuildConfig
import com.deepstudent.features.chat.ui.ModelPlaygroundScreen
import com.deepstudent.features.workspace.api.CloseResult
import com.deepstudent.features.workspace.api.OpenPanel
import com.deepstudent.features.workspace.api.PanelInstance
import com.deepstudent.features.workspace.api.PanelKey
import com.deepstudent.features.workspace.api.Workspace
import com.deepstudent.features.workspace.api.WorkspacePanelKeys
import com.deepstudent.features.workspace.ui.PanelLauncher
import kotlinx.coroutines.launch

internal val playgroundPanelKey = PanelKey.parse("llm-playground")
internal const val PLAYGROUND_TITLE = "LLM Playground"
internal const val PLAYGROUND_ROUTE = "debug/models"

internal fun playgroundLauncher(ports: WorkspacePorts): List<PanelLauncher> =
    if (ports.tools.hasPlayground()) listOf(PanelLauncher(playgroundPanelKey, PLAYGROUND_TITLE)) else emptyList()

internal fun WorkspaceToolPorts.hasPlayground(): Boolean = BuildConfig.DEBUG && playground != null

internal fun playgroundHosts(tools: WorkspaceToolPorts): List<ReadOnlyPanelAdapter> =
    if (tools.hasPlayground()) listOf(ReadOnlyPanelAdapter(playgroundPanelKey, PLAYGROUND_TITLE, "llm-playground")) else emptyList()

internal suspend fun removeRestoredPlayground(workspace: Workspace) {
    val restored = workspace.state.value.layout.panels.filter { it.key == playgroundPanelKey }
    for (panel in restored) workspace.close(panel.id)
}

internal fun playgroundContent(ports: WorkspacePorts): Map<PanelKey, NativePanelContent> = if (ports.tools.hasPlayground()) {
    mapOf(playgroundPanelKey to { panel, active -> WorkspacePlayground(ports, panel, active) })
} else {
    emptyMap()
}

@Suppress("ktlint:standard:function-naming") // Compose entry points use PascalCase.
@Composable
private fun WorkspacePlayground(ports: WorkspacePorts, panel: PanelInstance, active: Boolean) {
    val playground = ports.tools.playground ?: return
    val scope = rememberCoroutineScope()
    val leave: () -> Unit = { scope.launch { leavePlayground(ports, panel) } }
    BackHandler(enabled = active, onBack = leave)
    Box(Modifier.testTag(PLAYGROUND_ROUTE)) { ModelPlaygroundScreen(playground, onBack = leave) }
}

private suspend fun leavePlayground(ports: WorkspacePorts, panel: PanelInstance) {
    if (ports.workspace.close(panel.id) != CloseResult.Closed) return
    val notes = ports.workspace.state.value.layout.panels.firstOrNull { it.key == WorkspacePanelKeys.Notes }
    if (notes == null) ports.workspace.open(OpenPanel(WorkspacePanelKeys.Notes)) else ports.workspace.focus(notes.id)
}

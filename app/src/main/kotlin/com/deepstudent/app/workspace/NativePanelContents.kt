package com.deepstudent.app.workspace

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.deepstudent.app.content.BrowserEntry
import com.deepstudent.app.content.BrowserEntryHost
import com.deepstudent.app.content.ContentEntry
import com.deepstudent.app.execution.ExecutionPanel
import com.deepstudent.app.terminal.TerminalPanel
import com.deepstudent.core.common.id.TaskId
import com.deepstudent.features.chat.ui.ChatStreamPanel
import com.deepstudent.features.workspace.api.PanelInstance
import com.deepstudent.features.workspace.api.PanelKey
import com.deepstudent.features.workspace.api.ResourceKey
import com.deepstudent.features.workspace.api.WorkspacePanelKeys
import kotlinx.coroutines.launch

internal typealias NativePanelContent = @Composable (PanelInstance, Boolean) -> Unit

@Composable
internal fun nativePanelContents(ports: WorkspacePorts): Map<PanelKey, NativePanelContent> {
    val scope = rememberCoroutineScope()
    return remember(ports, scope) {
        mapOf<PanelKey, NativePanelContent>(
            PanelKey.parse("content") to { _, _ -> ContentEntry() },
            WorkspacePanelKeys.Notes to { panel, active -> PanelViewModels(panel.id) { WorkspaceNotesEntry(ports, panel, active) } },
            WorkspacePanelKeys.Pdf to { panel, _ -> WorkspacePdfEntry(ports, panel) },
            WorkspacePanelKeys.Chat to { panel, _ ->
                ChatStreamPanel(ports.chat, panel.resource?.id?.let(TaskId::parse), { task ->
                    scope.launch { ports.workspace.updateResource(panel.id, ResourceKey("chat-task", task.value)) }
                })
            },
            WorkspacePanelKeys.Browser to { panel, active ->
                BrowserEntry(
                    onClose = { scope.launch { ports.workspace.close(panel.id) } },
                    host = BrowserEntryHost(panel.resource?.id, active, { url ->
                        ports.workspace.updateResource(panel.id, ResourceKey("browser-url", url))
                    }, ports.testLoopback),
                )
            },
            PanelKey.parse("environments") to { panel, _ ->
                val loadManager = ports.environments
                if (loadManager == null) {
                    Text("环境安装未配置")
                } else {
                    com.deepstudent.app.environments.EnvironmentPanel(loadManager, {
                        scope.launch { ports.workspace.close(panel.id) }
                    })
                }
            },
            PanelKey.parse("shell-console") to { _, _ -> ShellWorkspacePanel(ports, scope) },
            PanelKey.parse("shizuku") to { panel, _ ->
                PanelViewModels(panel.id) { ExecutionPanel(ports.execution) }
            },
            PanelKey.parse("backup-preview") to { _, _ ->
                com.deepstudent.app.backup.BackupPreviewEntry()
            },
            PanelKey.parse("sync") to { _, _ ->
                com.deepstudent.app.sync.SyncEntry(ports.sync)
            },
            PanelKey.parse("terminal") to { panel, _ ->
                val terminal = ports.terminal
                if (terminal == null) {
                    Text("终端未配置")
                } else {
                    TerminalPanel(terminal) {
                        scope.launch { ports.workspace.close(panel.id) }
                    }
                }
            },
        ) + backgroundPanels(ports.tools.runtime) + sharingPanels(ports.tools) + playgroundContent(ports)
    }
}

@Suppress("ktlint:standard:function-naming") // Compose entry points use PascalCase.
@Composable
private fun ShellWorkspacePanel(ports: WorkspacePorts, scope: kotlinx.coroutines.CoroutineScope) {
    val shell = ports.tools.runtime.shell
    if (shell == null) {
        Text("命令执行未配置")
    } else {
        com.deepstudent.app.execution.ShellConsolePanel(shell) { scope.launch { openApprovals(ports.workspace) } }
    }
}

private suspend fun openApprovals(workspace: com.deepstudent.features.workspace.api.Workspace) {
    val key = PanelKey.parse("approvals")
    val existing = workspace.state.value.layout.panels.firstOrNull { it.key == key }
    if (existing == null) workspace.open(com.deepstudent.features.workspace.api.OpenPanel(key)) else workspace.focus(existing.id)
}

private fun backgroundPanels(tools: WorkspaceRuntimeTools): Map<PanelKey, NativePanelContent> = mapOf(
    PanelKey.parse("approvals") to { _, _ ->
        val approvals = tools.approvals
        if (approvals == null) Text("审批未配置") else com.deepstudent.app.execution.CapabilityApprovalPanel(approvals)
    },
    PanelKey.parse("schedules") to { _, _ -> tools.schedules?.let { com.deepstudent.app.scheduling.SchedulePanel(it) } },
    PanelKey.parse("background-power") to { _, _ -> tools.backgroundPower?.let { com.deepstudent.app.power.BackgroundPowerEntry(it) } },
    PanelKey.parse("note-import") to { _, _ ->
        val actions = tools.noteImport
        if (actions == null) Text("导入未配置") else com.deepstudent.app.runtime.NoteImportPanel(actions)
    },
)

private fun sharingPanels(tools: WorkspaceToolPorts): Map<PanelKey, NativePanelContent> = mapOf(
    WorkspaceSharingPanelKey to { _, _ -> tools.documents?.let { WorkspaceSharingPanel(it) } },
)

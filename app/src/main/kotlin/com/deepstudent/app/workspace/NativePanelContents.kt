package com.deepstudent.app.workspace

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
import androidx.compose.material3.Text
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
        mapOf(
            PanelKey.parse("content") to { _, _ -> ContentEntry() },
            WorkspacePanelKeys.Notes to { panel, active -> PanelViewModels(panel.id) { WorkspaceNotesEntry(ports, panel, active) } },
            WorkspacePanelKeys.Pdf to { panel, _ -> WorkspacePdfEntry(ports, panel) },
            WorkspacePanelKeys.Chat to { panel, _ ->
                ChatStreamPanel(ports.chat, panel.resource?.id?.let(TaskId::parse), { task ->
                    scope.launch { ports.workspace.updateResource(panel.id, ResourceKey("chat-task", task.value)) }
                })
            },
            WorkspacePanelKeys.Browser to { panel, active ->
                BrowserEntry(onClose = { scope.launch { ports.workspace.close(panel.id) } },
                    host = BrowserEntryHost(panel.resource?.id, active, { url ->
                        ports.workspace.updateResource(panel.id, ResourceKey("browser-url", url))
                    }, ports.testLoopback))
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
        )
    }
}

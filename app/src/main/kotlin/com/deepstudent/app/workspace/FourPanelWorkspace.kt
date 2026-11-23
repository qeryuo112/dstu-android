package com.deepstudent.app.workspace

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.deepstudent.features.workspace.api.OpenPanel
import com.deepstudent.features.workspace.api.PanelKey
import com.deepstudent.features.workspace.api.WorkspacePanelKeys
import com.deepstudent.features.workspace.ui.PanelLauncher
import com.deepstudent.features.workspace.ui.WorkspaceRoute

private val launchers = listOf(
    PanelLauncher(WorkspacePanelKeys.Notes, "笔记"),
    PanelLauncher(PanelKey.parse("note-import"), "流式导入"),
    PanelLauncher(PanelKey.parse("background-power"), "后台与电池"),
    PanelLauncher(PanelKey.parse("schedules"), "计划与提醒"),
    PanelLauncher(WorkspacePanelKeys.Pdf, "PDF"),
    PanelLauncher(WorkspacePanelKeys.Chat, "聊天"),
    PanelLauncher(WorkspacePanelKeys.Browser, "浏览器"),
    PanelLauncher(PanelKey.parse("content"), "内容"),
    PanelLauncher(PanelKey.parse("terminal"), "终端"),
    PanelLauncher(PanelKey.parse("environments"), "环境安装"),
    PanelLauncher(PanelKey.parse("shizuku"), "Shizuku"),
    PanelLauncher(PanelKey.parse("sync"), "同步配置"),
    PanelLauncher(PanelKey.parse("backup-preview"), "备份检查"),
)

@Suppress("ktlint:standard:function-naming") // Compose entry points use PascalCase.
@Composable
internal fun FourPanelWorkspace(ports: WorkspacePorts, openSchedules: Int = 0) {
    LaunchedEffect(ports, openSchedules) {
        if (openSchedules > 0 && ports.tools.schedules != null) {
            val key = PanelKey.parse("schedules")
            val existing = ports.workspace.state.value.layout.panels.firstOrNull { it.key == key }
            if (existing == null) ports.workspace.open(OpenPanel(key)) else ports.workspace.focus(existing.id)
        }
    }
    val contents = nativePanelContents(ports)
    WorkspaceRoute(ports.workspace, launchers) { panel, active -> contents[panel.key]?.invoke(panel, active) }
}

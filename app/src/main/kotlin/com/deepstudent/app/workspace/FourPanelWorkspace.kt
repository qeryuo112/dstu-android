package com.deepstudent.app.workspace

import androidx.compose.runtime.Composable
import com.deepstudent.features.workspace.api.PanelKey
import com.deepstudent.features.workspace.api.WorkspacePanelKeys
import com.deepstudent.features.workspace.ui.PanelLauncher
import com.deepstudent.features.workspace.ui.WorkspaceRoute

private val launchers = listOf(
    PanelLauncher(WorkspacePanelKeys.Notes, "笔记"),
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

@Composable
internal fun FourPanelWorkspace(ports: WorkspacePorts) {
    val contents = nativePanelContents(ports)
    WorkspaceRoute(ports.workspace, launchers) { panel, active -> contents[panel.key]?.invoke(panel, active) }
}

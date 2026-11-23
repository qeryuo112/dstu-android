package com.deepstudent.app.workspace

import android.content.Context
import com.deepstudent.app.storage.AndroidOperationLogger
import com.deepstudent.features.chat.api.ChatStreams
import com.deepstudent.features.documents.data.AndroidPdfPages
import com.deepstudent.features.notes.api.Notes
import com.deepstudent.features.workspace.api.OpenPanel
import com.deepstudent.features.workspace.api.PanelKey
import com.deepstudent.features.workspace.api.WorkspacePanelKeys
import com.deepstudent.features.workspace.api.WorkspaceStatus
import com.deepstudent.features.workspace.backend.application.PanelHostRegistry
import com.deepstudent.features.workspace.backend.application.WorkspaceSession
import com.deepstudent.features.workspace.data.AtomicWorkspaceStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/** App-process composition; consumers receive only their typed ports. */
internal class MainWorkspaceOwner {
    private val mutex = Mutex()
    private var ports: WorkspacePorts? = null

    suspend fun open(context: Context, notes: Notes, chat: ChatStreams, tools: WorkspaceToolPorts = WorkspaceToolPorts()): WorkspacePorts =
        mutex.withLock {
            ports ?: createWorkspacePorts(context, notes, chat, tools).also { ports = it }
        }
}

internal suspend fun createWorkspacePorts(
    context: Context,
    notes: Notes,
    chat: ChatStreams,
    tools: WorkspaceToolPorts = WorkspaceToolPorts(),
): WorkspacePorts {
    val store = AtomicWorkspaceStore(File(context.filesDir, "local/workspace"))
    val registry = PanelHostRegistry(
        listOf(
            NotesPanelAdapter(notes),
            ReadOnlyPanelAdapter(WorkspacePanelKeys.Pdf, "PDF", "pdf"),
            ReadOnlyPanelAdapter(WorkspacePanelKeys.Chat, "聊天", "chat-task"),
            ReadOnlyPanelAdapter(WorkspacePanelKeys.Browser, "浏览器", "browser-url"),
            ReadOnlyPanelAdapter(PanelKey.parse("content"), "内容", "content"),
            ReadOnlyPanelAdapter(PanelKey.parse("terminal"), "终端", "terminal"),
            ReadOnlyPanelAdapter(PanelKey.parse("environments"), "环境安装", "environments"),
            ReadOnlyPanelAdapter(PanelKey.parse("shizuku"), "Shizuku", "shizuku"),
            ReadOnlyPanelAdapter(PanelKey.parse("sync"), "同步配置", "sync"),
            ReadOnlyPanelAdapter(PanelKey.parse("backup-preview"), "备份检查", "backup-preview"),
            ReadOnlyPanelAdapter(PanelKey.parse("note-import"), "流式导入", "note-import"),
            ReadOnlyPanelAdapter(PanelKey.parse("background-power"), "后台与电池", "background-power"),
            ReadOnlyPanelAdapter(PanelKey.parse("schedules"), "计划与提醒", "schedules"),
        ),
    )
    val session = WorkspaceSession(registry, store, store, logger = AndroidOperationLogger)
    session.restore()
    check(session.state.value.status == WorkspaceStatus.Ready) { "workspace restore unavailable" }
    if (session.state.value.layout.panels.isEmpty()) session.open(OpenPanel(WorkspacePanelKeys.Notes))
    val bindings = PanelDraftBindings(session)
    return WorkspacePorts(
        DraftAwareWorkspace(session, bindings),
        notes,
        chat,
        AndroidPdfPages(context.contentResolver),
        bindings,
        tools = tools,
    )
}

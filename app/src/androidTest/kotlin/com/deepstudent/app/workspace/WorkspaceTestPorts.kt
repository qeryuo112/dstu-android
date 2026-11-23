package com.deepstudent.app.workspace

import android.content.Context
import android.content.ContextWrapper
import com.deepstudent.app.storage.MainStorageResources
import com.deepstudent.core.common.id.TaskId
import com.deepstudent.features.chat.api.ChatStreamRequest
import com.deepstudent.features.chat.api.ChatStreamSnapshot
import com.deepstudent.features.chat.api.ChatStreams
import com.deepstudent.features.documents.api.PdfPages
import com.deepstudent.features.documents.data.AndroidPdfPages
import com.deepstudent.features.terminal.api.TerminalSessions
import com.deepstudent.features.workspace.api.OpenPanel
import com.deepstudent.features.workspace.api.WorkspaceMode
import com.deepstudent.features.workspace.api.WorkspacePanelKeys
import com.deepstudent.services.execution.api.ExecutionSystem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import java.io.File

internal class WorkspaceTestPorts(context: Context, val root: File) : AutoCloseable {
    private val wrapper = object : ContextWrapper(context) {
        override fun getFilesDir() = root
    }
    val storage = MainStorageResources.open(wrapper)
    suspend fun open(
        chat: ChatStreams = NoChat,
        terminal: TerminalSessions? = null,
        environments: (suspend () -> com.deepstudent.features.environments.api.EnvironmentManager)? = null,
        execution: (suspend () -> ExecutionSystem)? = null,
    ): WorkspacePorts {
        val tools = WorkspaceToolPorts(terminal, environments, execution)
        val ports = createWorkspacePorts(wrapper, storage.notes, chat, tools)
        return WorkspacePorts(ports.workspace, ports.notes, ports.chat, ports.pdf, ports.drafts, testLoopback = true, tools = tools)
    }
    override fun close() {
        storage.close()
    }
}

private object NoChat : ChatStreams {
    override suspend fun start(request: ChatStreamRequest): TaskId = error("chat not configured")
    override fun observe(taskId: TaskId): Flow<ChatStreamSnapshot> = emptyFlow()
    override suspend fun cancel(taskId: TaskId) = Unit
}

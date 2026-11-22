package com.deepstudent.app.workspace

import com.deepstudent.features.chat.api.ChatStreams
import com.deepstudent.features.documents.api.PdfPages
import com.deepstudent.features.notes.api.Notes
import com.deepstudent.features.terminal.api.TerminalSessions
import com.deepstudent.features.workspace.api.Workspace
import com.deepstudent.features.environments.api.EnvironmentManager
import com.deepstudent.services.execution.api.ExecutionSystem

internal class WorkspacePorts(
    val workspace: Workspace,
    val notes: Notes,
    val chat: ChatStreams,
    val pdf: PdfPages,
    val drafts: PanelDraftBindings,
    val testLoopback: Boolean = false,
    val tools: WorkspaceToolPorts = WorkspaceToolPorts(),
) {
    val terminal: TerminalSessions? get() = tools.terminal
    val environments: (suspend () -> EnvironmentManager)? get() = tools.environments
    val execution: (suspend () -> ExecutionSystem)? get() = tools.execution
    val sync: (suspend () -> com.deepstudent.features.sync.ui.SyncController)? get() = tools.sync
}

/** Narrow optional, lazy tool entry ports; no tool implementations or task state. */
internal class WorkspaceToolPorts(
    val terminal: TerminalSessions? = null,
    val environments: (suspend () -> EnvironmentManager)? = null,
    val execution: (suspend () -> ExecutionSystem)? = null,
    val sync: (suspend () -> com.deepstudent.features.sync.ui.SyncController)? = null,
)

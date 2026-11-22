package com.deepstudent.features.workspace.ui

import com.deepstudent.features.workspace.api.CloseDecision
import com.deepstudent.features.workspace.api.OpenPanel
import com.deepstudent.features.workspace.api.PanelId
import com.deepstudent.features.workspace.api.PanelKey

data class PanelLauncher(val key: PanelKey, val title: String)

sealed interface WorkspaceAction {
    data class Open(val key: PanelKey) : WorkspaceAction
    data class Focus(val id: PanelId) : WorkspaceAction
    data class Close(val id: PanelId) : WorkspaceAction
    data class Decide(val decision: CloseDecision) : WorkspaceAction
    data class AcknowledgeSave(val id: PanelId) : WorkspaceAction
    data object Retry : WorkspaceAction
}

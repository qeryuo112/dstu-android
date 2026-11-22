package com.deepstudent.features.workspace.api

import kotlinx.coroutines.flow.StateFlow

/**
 * Narrow workspace facade observed by UI. All mutating calls are serialized by
 * the implementation; observed state is immutable.
 */
interface Workspace {
    val state: StateFlow<WorkspaceState>

    suspend fun restore()

    suspend fun open(command: OpenPanel): PanelInstance

    suspend fun focus(panelId: PanelId)

    suspend fun loadDraft(panelId: PanelId): PanelDraft?

    suspend fun updateDraft(panelId: PanelId, bytes: ByteArray): DraftWrite

    /**
     * Clears the opaque local draft after the domain already committed it and
     * marks the panel clean. This never calls [PanelHost.save]. A null draft
     * notification must not call this method while workspace restore is pending.
     */
    suspend fun clearDraft(panelId: PanelId): Boolean

    /** Updates the existing panel resource without creating a panel or changing dirty state. */
    suspend fun updateResource(panelId: PanelId, resource: ResourceKey?)

    suspend fun save(panelId: PanelId): SaveOutcome

    /** Explicitly releases a panel after the user resolved an uncertain save result. */
    suspend fun acknowledgeSaveUncertain(panelId: PanelId)

    suspend fun close(panelId: PanelId): CloseResult

    suspend fun resolveClose(decision: CloseDecision): CloseResult

    fun cancelClose()

    suspend fun setMode(mode: WorkspaceMode)
}

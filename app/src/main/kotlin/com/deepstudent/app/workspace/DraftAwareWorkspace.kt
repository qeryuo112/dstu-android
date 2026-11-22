package com.deepstudent.app.workspace

import com.deepstudent.features.workspace.api.CloseDecision
import com.deepstudent.features.workspace.api.CloseResult
import com.deepstudent.features.workspace.api.PanelId
import com.deepstudent.features.workspace.api.SaveOutcome
import com.deepstudent.features.workspace.api.Workspace

/** Flushes the domain's current editor snapshot before delegating to the single close coordinator. */
internal class DraftAwareWorkspace(private val delegate: Workspace, private val bindings: PanelDraftBindings) : Workspace by delegate {
    override suspend fun close(panelId: PanelId): CloseResult {
        bindings.flush(panelId, requireIdle = true)
        return delegate.close(panelId)
    }

    override suspend fun save(panelId: PanelId): SaveOutcome {
        bindings.flush(panelId, requireIdle = true)
        return delegate.save(panelId)
    }

    override suspend fun resolveClose(decision: CloseDecision): CloseResult {
        delegate.state.value.pendingClose?.panelId?.let { bindings.flush(it, requireIdle = true) }
        return delegate.resolveClose(decision)
    }
}

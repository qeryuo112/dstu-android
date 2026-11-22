package com.deepstudent.features.workspace.backend.domain

import com.deepstudent.features.workspace.api.CloseDecision
import com.deepstudent.features.workspace.api.PanelInstance
import com.deepstudent.features.workspace.api.PendingClose

sealed interface CloseGuardRequest {
    data object Immediate : CloseGuardRequest
    data class Prompt(val pending: PendingClose) : CloseGuardRequest
    data class Busy(val pending: PendingClose) : CloseGuardRequest
}

sealed interface CloseGuardResolution {
    data object Cancel : CloseGuardResolution
    data class Save(val pending: PendingClose) : CloseGuardResolution
    data class Discard(val pending: PendingClose) : CloseGuardResolution
    data object Invalid : CloseGuardResolution
}

/** Pure close policy. Persistence and domain callbacks belong to the application layer. */
class CloseGuard {
    fun request(panel: PanelInstance, pending: PendingClose?): CloseGuardRequest {
        if (pending != null) return CloseGuardRequest.Busy(pending)
        if (panel.saveUncertain) return CloseGuardRequest.Busy(
            PendingClose(panel.id, panel.key, dirty = panel.dirty, saveUncertain = true),
        )
        if (!panel.dirty) return CloseGuardRequest.Immediate
        return CloseGuardRequest.Prompt(PendingClose(panel.id, panel.key, dirty = true))
    }

    fun resolve(
        pending: PendingClose?,
        panel: PanelInstance?,
        decision: CloseDecision,
    ): CloseGuardResolution {
        if (pending == null || panel == null || pending.panelId != panel.id) {
            return CloseGuardResolution.Invalid
        }
        return when (decision) {
            CloseDecision.Cancel -> CloseGuardResolution.Cancel
            CloseDecision.Save -> CloseGuardResolution.Save(pending)
            CloseDecision.Discard -> CloseGuardResolution.Discard(pending)
        }
    }
}

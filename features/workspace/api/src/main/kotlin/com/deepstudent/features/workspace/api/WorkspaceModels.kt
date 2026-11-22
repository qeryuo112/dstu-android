package com.deepstudent.features.workspace.api

import com.deepstudent.core.common.error.DstuError
import com.deepstudent.core.common.error.DstuException

enum class WorkspaceMode {
    PhoneStack,
    TabletPanels,
}

enum class WorkspaceStatus {
    Loading,
    Ready,
    Error,
}

data class PanelInstance(
    val id: PanelId,
    val key: PanelKey,
    val resource: ResourceKey?,
    val title: String,
    val dirty: Boolean = false,
    val draftRevision: Long = 0,
    val saveUncertain: Boolean = false,
) {
    init {
        if (title.length > MAX_TITLE_CHARS || title.any { it.code < 0x20 && it != '\t' }) {
            throw DstuException(DstuError.validation("INVALID_PANEL_TITLE", "invalid panel title"))
        }
    }

    companion object {
        const val MAX_TITLE_CHARS: Int = 300
    }
}

data class LayoutState(
    val mode: WorkspaceMode = WorkspaceMode.PhoneStack,
    val panels: List<PanelInstance> = emptyList(),
    val activePanelId: PanelId? = null,
    val revision: Long = 0,
) {
    init {
        require(panels.size <= MAX_PANELS) { "workspace layout exceeds panel limit" }
        require(panels.map { it.id }.toSet().size == panels.size) { "duplicate panel id" }
        require(activePanelId == null || panels.any { it.id == activePanelId }) {
            "active panel is not present"
        }
        require(revision >= 0) { "workspace layout revision must be non-negative" }
    }

    fun panel(panelId: PanelId): PanelInstance? = panels.firstOrNull { it.id == panelId }

    fun activePanel(): PanelInstance? = activePanelId?.let(::panel)

    companion object {
        const val MAX_PANELS: Int = 64
    }
}

data class PendingClose(
    val panelId: PanelId,
    val panelKey: PanelKey,
    val dirty: Boolean,
    val saveUncertain: Boolean = false,
)

data class WorkspaceFeedback(val code: String, val message: String)

data class WorkspaceState(
    val status: WorkspaceStatus = WorkspaceStatus.Loading,
    val layout: LayoutState = LayoutState(),
    val pendingClose: PendingClose? = null,
    val savingPanelId: PanelId? = null,
    val uncertainPanelId: PanelId? = null,
    val feedback: WorkspaceFeedback? = null,
) {
    val activePanel: PanelInstance? get() = layout.activePanel()

    val canClose: Boolean get() = savingPanelId == null
}

data class OpenPanel(
    val key: PanelKey,
    val resource: ResourceKey? = null,
    val title: String? = null,
)

sealed interface CloseDecision {
    data object Cancel : CloseDecision
    data object Save : CloseDecision
    data object Discard : CloseDecision
}

sealed interface CloseResult {
    data object Closed : CloseResult
    data object NeedsDecision : CloseResult
    data object Cancelled : CloseResult
    data class SaveFailed(val error: DstuError) : CloseResult
    data class DiscardFailed(val error: DstuError) : CloseResult
}

sealed interface SaveOutcome {
    data object Saved : SaveOutcome
    data object NoChanges : SaveOutcome
    data object SavedWithNewerDraft : SaveOutcome
    data class Failed(val error: DstuError) : SaveOutcome
    data class Uncertain(val error: DstuError) : SaveOutcome
}

data class DraftWrite(val revision: Long, val updatedAtEpochMs: Long)

data class DraftLoad(val panelId: PanelId, val draft: PanelDraft?)

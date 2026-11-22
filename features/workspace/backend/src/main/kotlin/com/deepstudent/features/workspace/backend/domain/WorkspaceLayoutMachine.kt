package com.deepstudent.features.workspace.backend.domain

import com.deepstudent.core.common.error.DstuError
import com.deepstudent.core.common.error.DstuException
import com.deepstudent.features.workspace.api.LayoutState
import com.deepstudent.features.workspace.api.PanelDraft
import com.deepstudent.features.workspace.api.PanelId
import com.deepstudent.features.workspace.api.PanelInstance
import com.deepstudent.features.workspace.api.ResourceKey
import com.deepstudent.features.workspace.api.WorkspaceMode

data class LayoutOpen(
    val layout: LayoutState,
    val panel: PanelInstance,
    val reused: Boolean,
)

/** Pure layout transitions. It stores no panel content and performs no I/O. */
class WorkspaceLayoutMachine {
    fun normalize(layout: LayoutState): LayoutState {
        if (layout.panels.isEmpty()) {
            return if (layout.activePanelId == null) layout else layout.copy(activePanelId = null)
        }
        val activePanelId = layout.activePanelId
        if (activePanelId != null && layout.panel(activePanelId) != null) return layout
        return layout.copy(activePanelId = layout.panels.first().id, revision = next(layout.revision))
    }

    fun open(layout: LayoutState, candidate: PanelInstance): LayoutOpen {
        val existing = find(layout, candidate.key, candidate.resource)
        if (existing != null) {
            return LayoutOpen(focus(layout, existing.id), existing, reused = true)
        }
        if (layout.panels.size >= LayoutState.MAX_PANELS) {
            throw DstuException(
                DstuError.conflict("WORKSPACE_PANEL_LIMIT", "workspace panel limit reached"),
            )
        }
        val next = layout.copy(
            panels = layout.panels + candidate,
            activePanelId = candidate.id,
            revision = next(layout.revision),
        )
        return LayoutOpen(next, candidate, reused = false)
    }

    fun focus(layout: LayoutState, panelId: PanelId): LayoutState {
        if (layout.panel(panelId) == null || layout.activePanelId == panelId) return layout
        return layout.copy(activePanelId = panelId, revision = next(layout.revision))
    }

    fun remove(layout: LayoutState, panelId: PanelId): LayoutState {
        val index = layout.panels.indexOfFirst { it.id == panelId }
        if (index < 0) return layout
        val remaining = layout.panels.filterNot { it.id == panelId }
        val active = when {
            layout.activePanelId != panelId -> layout.activePanelId
            remaining.isEmpty() -> null
            index >= remaining.size -> remaining.last().id
            else -> remaining[index].id
        }
        return layout.copy(
            panels = remaining,
            activePanelId = active,
            revision = next(layout.revision),
        )
    }

    fun setMode(layout: LayoutState, mode: WorkspaceMode): LayoutState {
        if (layout.mode == mode) return layout
        return layout.copy(mode = mode, revision = next(layout.revision))
    }

    fun markDraft(layout: LayoutState, panelId: PanelId, revision: Long): LayoutState =
        updatePanel(layout, panelId) {
            it.copy(dirty = true, draftRevision = revision)
        }

    fun markClean(layout: LayoutState, panelId: PanelId): LayoutState =
        updatePanel(layout, panelId) {
            it.copy(dirty = false)
        }

    fun markSaved(
        layout: LayoutState,
        panelId: PanelId,
        resource: ResourceKey?,
        title: String?,
    ): LayoutState = updatePanel(layout, panelId) { panel ->
        panel.copy(
            resource = resource ?: panel.resource,
            title = title ?: panel.title,
            dirty = false,
        )
    }

    fun updateResource(
        layout: LayoutState,
        panelId: PanelId,
        resource: ResourceKey?,
    ): LayoutState = updatePanel(layout, panelId) { it.copy(resource = resource) }

    fun updateMetadata(
        layout: LayoutState,
        panelId: PanelId,
        resource: ResourceKey?,
        title: String?,
    ): LayoutState = updatePanel(layout, panelId) { panel ->
        panel.copy(
            resource = resource ?: panel.resource,
            title = title ?: panel.title,
        )
    }

    fun markUncertain(layout: LayoutState, panelId: PanelId): LayoutState =
        updatePanel(layout, panelId) { it.copy(saveUncertain = true) }

    fun clearUncertain(layout: LayoutState, panelId: PanelId): LayoutState =
        updatePanel(layout, panelId) { it.copy(saveUncertain = false) }

    fun reconcileDrafts(
        layout: LayoutState,
        drafts: Map<PanelId, PanelDraft>,
    ): LayoutState {
        var changed = false
        val panels = layout.panels.map { panel ->
            val draft = drafts[panel.id]
            val dirty = draft != null
            val revision = draft?.revision ?: 0
            if (panel.dirty == dirty && panel.draftRevision == revision) {
                panel
            } else {
                changed = true
                panel.copy(dirty = dirty, draftRevision = revision)
            }
        }
        return if (changed) {
            layout.copy(panels = panels, activePanelId = activeAfter(layout), revision = next(layout.revision))
        } else {
            layout
        }
    }

    private fun updatePanel(
        layout: LayoutState,
        panelId: PanelId,
        transform: (PanelInstance) -> PanelInstance,
    ): LayoutState {
        val index = layout.panels.indexOfFirst { it.id == panelId }
        if (index < 0) return layout
        val updated = transform(layout.panels[index])
        if (updated == layout.panels[index]) return layout
        val panels = layout.panels.toMutableList().apply { this[index] = updated }
        return layout.copy(panels = panels, revision = next(layout.revision))
    }

    private fun activeAfter(layout: LayoutState): PanelId? =
        layout.activePanelId?.takeIf { id -> layout.panels.any { it.id == id } }

    private fun find(layout: LayoutState, key: com.deepstudent.features.workspace.api.PanelKey, resource: ResourceKey?) =
        layout.panels.firstOrNull { it.key == key && it.resource == resource }

    private fun next(revision: Long): Long =
        if (revision == Long.MAX_VALUE) Long.MAX_VALUE else revision + 1
}

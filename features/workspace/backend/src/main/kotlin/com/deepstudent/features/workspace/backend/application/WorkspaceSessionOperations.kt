package com.deepstudent.features.workspace.backend.application

import com.deepstudent.core.common.error.DstuError
import com.deepstudent.core.common.error.DstuException
import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.features.workspace.api.DraftWrite
import com.deepstudent.features.workspace.api.LayoutState
import com.deepstudent.features.workspace.api.OpenPanel
import com.deepstudent.features.workspace.api.PanelDraft
import com.deepstudent.features.workspace.api.PanelId
import com.deepstudent.features.workspace.api.PanelInstance
import com.deepstudent.features.workspace.api.ResourceKey
import com.deepstudent.features.workspace.api.WorkspaceMode
import com.deepstudent.features.workspace.api.WorkspaceState
import com.deepstudent.features.workspace.api.WorkspaceStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.withLock

internal suspend fun WorkspaceSession.restoreWorkspace() = mutex.withLock {
    if (state.value.status == WorkspaceStatus.Ready) return@withLock
    mutableState.value = WorkspaceState(status = WorkspaceStatus.Loading)
    try {
        val stored = snapshots.load()?.let(machine::normalize)
        val base = stored ?: LayoutState()
        val draftsByPanel = base.panels.associate { panel ->
            panel.id to loadDraftOrNull(panel.id)
        }.filterValues { it != null }.mapValues { it.value!! }
        val layout = machine.reconcileDrafts(base, draftsByPanel)
        mutableState.value = WorkspaceState(status = WorkspaceStatus.Ready, layout = layout)
        if (stored != null && layout != stored) persistState(state.value)
        record(
            "workspace.restored",
            "panels" to JsonValue.integer(layout.panels.size.toLong()),
            "dirty" to JsonValue.integer(layout.panels.count { it.dirty }.toLong()),
        )
    } catch (cancelled: CancellationException) {
        mutableState.value = WorkspaceState()
        throw cancelled
    } catch (failure: Exception) {
        mutableState.value = WorkspaceState(
            status = WorkspaceStatus.Error,
            feedback = com.deepstudent.features.workspace.api.WorkspaceFeedback(
                "WORKSPACE_RESTORE_FAILED",
                "工作区恢复失败",
            ),
        )
        record(
            "workspace.restore.failed",
            "error" to JsonValue.text(failure.asWorkspaceError("WORKSPACE_RESTORE_FAILED").code),
        )
    }
}

internal suspend fun WorkspaceSession.openPanel(command: OpenPanel): PanelInstance = mutex.withLock {
    requireReady()
    val host = hostOrThrow(command.key)
    if (!host.accepts(command.resource)) {
        throw DstuException(
            DstuError.validation("WORKSPACE_RESOURCE_REJECTED", "workspace host rejected resource"),
        )
    }
    val current = state.value
    val existing = current.layout.panels.firstOrNull {
        it.key == command.key && it.resource == command.resource
    }
    if (existing != null) {
        val layout = machine.focus(current.layout, existing.id)
        publish(layout)
        persistState(state.value)
        record("workspace.panel.focused", "panelId" to JsonValue.text(existing.id.value))
        return@withLock existing
    }
    val candidate = PanelInstance(
        id = newPanelId(current.layout),
        key = command.key,
        resource = command.resource,
        title = workspacePanelTitle(command.title, host.title.ifBlank { command.key.value }),
    )
    val opened = machine.open(current.layout, candidate)
    publish(opened.layout)
    persistState(state.value)
    record(
        "workspace.panel.opened",
        "panelId" to JsonValue.text(opened.panel.id.value),
        "panelKey" to JsonValue.text(opened.panel.key.value),
    )
    opened.panel
}

internal suspend fun WorkspaceSession.focusPanel(panelId: PanelId) = mutex.withLock {
    requireReady()
    val current = state.value
    val layout = machine.focus(current.layout, panelId)
    if (layout == current.layout) return@withLock
    publish(layout)
    persistState(state.value)
    record("workspace.panel.focused", "panelId" to JsonValue.text(panelId.value))
}

internal suspend fun WorkspaceSession.writeDraft(
    panelId: PanelId,
    bytes: ByteArray,
    persistLayout: Boolean = true,
): DraftWrite =
    mutex.withLock {
        requireReady()
        val panel = panelOrThrow(panelId)
        val revision = nextDraftRevision(panel.draftRevision)
        val updatedAt = clock.now().toEpochMilli()
        val draft = try {
            PanelDraft(panelId, bytes, revision, updatedAt)
        } catch (failure: IllegalArgumentException) {
            throw DstuException(
                DstuError.validation("WORKSPACE_DRAFT_INVALID", "workspace draft is invalid"),
            )
        }
        val saved = try {
            drafts.save(draft)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            fail("WORKSPACE_DRAFT_WRITE_FAILED", "本地草稿保存失败")
            throw DstuException(
                failure.asWorkspaceError("WORKSPACE_DRAFT_WRITE_FAILED"),
                failure,
            )
        }
        val layout = machine.markDraft(state.value.layout, panelId, saved.revision)
        publish(layout)
        if (persistLayout) persistState(state.value)
        record(
            "workspace.draft.stored",
            "panelId" to JsonValue.text(panelId.value),
            "bytes" to JsonValue.integer(saved.size.toLong()),
            "revision" to JsonValue.integer(saved.revision),
        )
        DraftWrite(saved.revision, saved.updatedAtEpochMs)
    }

internal suspend fun WorkspaceSession.clearPersistedDraft(panelId: PanelId): Boolean =
    mutex.withLock {
        requireReady()
        panelOrThrow(panelId)
        val removed = try {
            drafts.delete(panelId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            fail("WORKSPACE_DRAFT_DELETE_FAILED", "本地草稿清理失败")
            throw DstuException(failure.asWorkspaceError("WORKSPACE_DRAFT_DELETE_FAILED"), failure)
        }
        val layout = machine.markClean(state.value.layout, panelId)
        publish(layout)
        persistState(state.value)
        record(
            "workspace.draft.cleared",
            "panelId" to JsonValue.text(panelId.value),
            "removed" to JsonValue.bool(removed),
        )
        removed
    }

internal suspend fun WorkspaceSession.changeResource(panelId: PanelId, resource: ResourceKey?) =
    mutex.withLock {
        requireReady()
        val panel = panelOrThrow(panelId)
        val host = hostOrThrow(panel.key)
        if (!host.accepts(resource)) {
            throw DstuException(
                DstuError.validation("WORKSPACE_RESOURCE_REJECTED", "workspace host rejected resource"),
            )
        }
        val duplicate = state.value.layout.panels.any {
            it.id != panelId && it.key == panel.key && it.resource == resource
        }
        if (duplicate) {
            throw DstuException(
                DstuError.conflict("WORKSPACE_RESOURCE_CONFLICT", "workspace resource is already open"),
            )
        }
        val layout = machine.updateResource(state.value.layout, panelId, resource)
        publish(layout)
        persistState(state.value)
        record(
            "workspace.resource.updated",
            "panelId" to JsonValue.text(panelId.value),
            "hasResource" to JsonValue.bool(resource != null),
        )
    }

internal suspend fun WorkspaceSession.changeMode(mode: WorkspaceMode) = mutex.withLock {
    requireReady()
    val current = state.value
    val layout = machine.setMode(current.layout, mode)
    if (layout == current.layout) return@withLock
    publish(layout)
    persistState(state.value)
    record("workspace.mode.changed", "mode" to JsonValue.text(mode.name))
}

internal suspend fun WorkspaceSession.acknowledgeUncertainSave(panelId: PanelId) =
    mutex.withLock {
        requireReady()
        val panel = panelOrThrow(panelId)
        if (!panel.saveUncertain) return@withLock
        val layout = machine.clearUncertain(state.value.layout, panelId)
        publish(layout)
        persistState(state.value)
        record("workspace.save.uncertain.acknowledged", "panelId" to JsonValue.text(panelId.value))
    }

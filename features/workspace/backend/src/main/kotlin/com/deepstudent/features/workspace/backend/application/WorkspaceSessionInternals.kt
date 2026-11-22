package com.deepstudent.features.workspace.backend.application

import com.deepstudent.core.common.error.DstuError
import com.deepstudent.core.common.error.DstuException
import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.features.workspace.api.LayoutState
import com.deepstudent.features.workspace.api.PanelDraft
import com.deepstudent.features.workspace.api.PanelHost
import com.deepstudent.features.workspace.api.PanelId
import com.deepstudent.features.workspace.api.PanelInstance
import com.deepstudent.features.workspace.api.PanelSaveContext
import com.deepstudent.features.workspace.api.PanelSaveResult
import com.deepstudent.features.workspace.api.PanelDiscardContext
import com.deepstudent.features.workspace.api.PendingClose
import com.deepstudent.features.workspace.api.WorkspaceFeedback
import com.deepstudent.features.workspace.api.WorkspaceState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.update

internal fun WorkspaceSession.requireReady() {
    if (state.value.status != com.deepstudent.features.workspace.api.WorkspaceStatus.Ready) {
        throw DstuException(
            DstuError.unavailable("WORKSPACE_NOT_READY", "workspace is not ready"),
        )
    }
}

internal fun WorkspaceSession.panelOrThrow(panelId: PanelId): PanelInstance =
    state.value.layout.panel(panelId) ?: throw DstuException(
        DstuError.notFound("WORKSPACE_PANEL_MISSING", "workspace panel is not open"),
    )

internal fun WorkspaceSession.hostOrThrow(key: com.deepstudent.features.workspace.api.PanelKey): PanelHost =
    hosts.requireHost(key)

internal fun WorkspaceSession.newPanelId(layout: LayoutState): PanelId {
    repeat(8) {
        val bytes = random.nextBytes(16)
        val value = bytes.joinToString("") { "%02x".format(it) }
        val panelId = PanelId.parse("panel-$value")
        if (layout.panel(panelId) == null) return panelId
    }
    throw DstuException(
        DstuError.unavailable("WORKSPACE_PANEL_ID_FAILED", "workspace could not allocate a panel"),
    )
}

internal fun WorkspaceSession.nextDraftRevision(previous: Long): Long {
    if (previous == Long.MAX_VALUE) {
        throw DstuException(
            DstuError.conflict("WORKSPACE_DRAFT_REVISION_EXHAUSTED", "workspace draft revision exhausted"),
        )
    }
    return previous + 1
}

internal fun WorkspaceSession.storageError(code: String): DstuError =
    DstuError.unavailable(code, "workspace storage is unavailable")

internal fun Throwable.asWorkspaceError(code: String): DstuError =
    (this as? DstuException)?.error ?: DstuError.unavailable(code, "workspace operation failed")

internal fun workspacePanelTitle(title: String?, fallback: String): String {
    val candidate = title?.takeIf { it.isNotBlank() } ?: fallback
    return candidate.takeIf { it.length <= PanelInstance.MAX_TITLE_CHARS } ?: candidate.take(PanelInstance.MAX_TITLE_CHARS)
}

internal fun WorkspaceSession.fail(code: String, message: String) {
    mutableState.update { it.copy(feedback = WorkspaceFeedback(code, message)) }
}

internal fun WorkspaceSession.record(event: String, vararg fields: Pair<String, JsonValue>) {
    try {
        logger.debug(event, JsonValue.obj(*fields))
    } catch (_: Exception) {
        // Diagnostics must never change workspace behavior.
    }
}

internal suspend fun WorkspaceSession.persist(layout: LayoutState): Boolean = try {
    snapshots.save(layout)
    true
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Exception) {
    fail("WORKSPACE_LAYOUT_NOT_SAVED", "工作区布局未能保存")
    record(
        "workspace.layout.persist.failed",
        "panels" to JsonValue.integer(layout.panels.size.toLong()),
        "error" to JsonValue.text(failure.asWorkspaceError("WORKSPACE_LAYOUT_NOT_SAVED").code),
    )
    false
}

internal suspend fun WorkspaceSession.persistOrThrow(layout: LayoutState) {
    try {
        snapshots.save(layout)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        throw DstuException(
            failure.asWorkspaceError("WORKSPACE_LAYOUT_NOT_SAVED"),
            failure,
        )
    }
}

internal suspend fun WorkspaceSession.persistState(current: WorkspaceState): Boolean = try {
    snapshots.save(current.layout)
    true
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Exception) {
    mutableState.value = state.value.copy(
        feedback = WorkspaceFeedback("WORKSPACE_LAYOUT_NOT_SAVED", "工作区布局未能保存"),
    )
    record(
        "workspace.layout.persist.failed",
        "panels" to JsonValue.integer(current.layout.panels.size.toLong()),
        "error" to JsonValue.text(failure.asWorkspaceError("WORKSPACE_LAYOUT_NOT_SAVED").code),
    )
    false
}

internal suspend fun WorkspaceSession.loadDraftOrNull(panelId: PanelId): PanelDraft? =
    drafts.load(panelId)

internal suspend fun WorkspaceSession.callHostSave(
    panel: PanelInstance,
    draft: PanelDraft?,
): PanelSaveResult {
    val host = hosts.find(panel.key) ?: return PanelSaveResult.Failed(
        DstuError.notFound("WORKSPACE_HOST_MISSING", "workspace panel host is not registered"),
    )
    return host.save(PanelSaveContext(panel, draft))
}

internal suspend fun WorkspaceSession.callHostDiscard(
    panel: PanelInstance,
    draft: PanelDraft?,
): DstuError? {
    val host = hosts.find(panel.key) ?: return DstuError.notFound(
        "WORKSPACE_HOST_MISSING",
        "workspace panel host is not registered",
    )
    return try {
        host.discard(PanelDiscardContext(panel, draft))
        null
    } catch (failure: Exception) {
        record(
            "workspace.discard.failed",
            "panelId" to JsonValue.text(panel.id.value),
            "error" to JsonValue.text(failure.asWorkspaceError("WORKSPACE_DISCARD_FAILED").code),
        )
        failure.asWorkspaceError("WORKSPACE_DISCARD_FAILED")
    }
}

internal suspend fun WorkspaceSession.closeNow(panelId: PanelId): Boolean {
    val current = state.value
    val panel = current.layout.panel(panelId) ?: return false
    val layout = machine.remove(current.layout, panelId)
    val next = current.copy(
        layout = layout,
        pendingClose = null,
        savingPanelId = null,
        uncertainPanelId = null,
        feedback = null,
    )
    if (!persistState(next)) {
        mutableState.value = state.value.copy(savingPanelId = null)
        return false
    }
    try {
        drafts.delete(panelId)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        record(
            "workspace.draft.delete.failed",
            "panelId" to JsonValue.text(panelId.value),
            "error" to JsonValue.text(failure.asWorkspaceError("WORKSPACE_DRAFT_DELETE_FAILED").code),
        )
        restoreSnapshot(current.layout)
        mutableState.value = state.value.copy(savingPanelId = null)
        throw DstuException(
            failure.asWorkspaceError("WORKSPACE_DRAFT_DELETE_FAILED"),
            failure,
        )
    }
    try {
        hosts.find(panel.key)?.onClosed(panel)
    } catch (_: Exception) {
        record("workspace.host.close.failed", "panelId" to JsonValue.text(panelId.value))
    }
    mutableState.value = next
    record("workspace.panel.closed", "panelId" to JsonValue.text(panelId.value))
    return true
}

private suspend fun WorkspaceSession.restoreSnapshot(layout: LayoutState) {
    try {
        snapshots.save(layout)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        record(
            "workspace.layout.rollback.failed",
            "panels" to JsonValue.integer(layout.panels.size.toLong()),
            "error" to JsonValue.text(failure.asWorkspaceError("WORKSPACE_LAYOUT_NOT_SAVED").code),
        )
    }
}

internal fun WorkspaceState.withLayout(layout: LayoutState): WorkspaceState =
    copy(layout = layout, feedback = null)

internal fun WorkspaceSession.publish(layout: LayoutState, pending: PendingClose? = null) {
    mutableState.value = state.value.copy(
        layout = layout,
        pendingClose = pending ?: state.value.pendingClose,
        uncertainPanelId = layout.panels.firstOrNull { it.saveUncertain }?.id,
        feedback = null,
    )
}

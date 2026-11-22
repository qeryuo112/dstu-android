package com.deepstudent.features.workspace.backend.application

import com.deepstudent.core.common.error.DstuError
import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.features.workspace.api.CloseDecision
import com.deepstudent.features.workspace.api.CloseResult
import com.deepstudent.features.workspace.api.PanelDraft
import com.deepstudent.features.workspace.api.PanelId
import com.deepstudent.features.workspace.api.PanelInstance
import com.deepstudent.features.workspace.api.PanelSaveResult
import com.deepstudent.features.workspace.api.ResourceKey
import com.deepstudent.features.workspace.api.SaveOutcome
import com.deepstudent.features.workspace.api.WorkspaceFeedback
import com.deepstudent.features.workspace.backend.domain.CloseGuardRequest
import com.deepstudent.features.workspace.backend.domain.CloseGuardResolution
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private data class SavePreparation(
    val panel: PanelInstance,
    val draft: PanelDraft?,
    val revision: Long,
)

private sealed interface PreparedSave {
    data class Ready(val value: SavePreparation) : PreparedSave
    data class Immediate(val outcome: SaveOutcome) : PreparedSave
}

private data class SavedMetadata(val resource: ResourceKey?, val title: String?)

private data class DiscardPreparation(
    val panel: PanelInstance,
    val draft: PanelDraft?,
    val revision: Long,
)

internal suspend fun WorkspaceSession.saveWorkspacePanel(panelId: PanelId): SaveOutcome {
    return withContext(NonCancellable) {
        val prepared = mutex.withLock { prepareSave(panelId) }
        if (prepared is PreparedSave.Immediate) return@withContext prepared.outcome
        val ready = (prepared as PreparedSave.Ready).value
        val hostResult = try {
            callHostSave(ready.panel, ready.draft)
        } catch (failure: Exception) {
            null
        }
        mutex.withLock {
            if (hostResult == null) {
                uncertainSave(panelId, failure = null)
            } else {
                finishSave(ready, hostResult)
            }
        }
    }
}

private suspend fun WorkspaceSession.prepareSave(panelId: PanelId): PreparedSave {
    requireReady()
    if (state.value.savingPanelId != null) {
        return PreparedSave.Immediate(
            SaveOutcome.Failed(DstuError.conflict("WORKSPACE_BUSY", "another save is active")),
        )
    }
    val panel = panelOrThrow(panelId)
    if (panel.saveUncertain) {
        return PreparedSave.Immediate(
            SaveOutcome.Uncertain(
                DstuError.conflict(
                    "WORKSPACE_SAVE_UNCERTAIN",
                    "previous save result must be acknowledged",
                ),
            ),
        )
    }
    val draft = try {
        loadDraftOrNull(panelId)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        return PreparedSave.Immediate(
            saveFailed(panelId, failure.asWorkspaceError("WORKSPACE_DRAFT_READ_FAILED")),
        )
    }
    if (!panel.dirty && draft == null) return PreparedSave.Immediate(SaveOutcome.NoChanges)
    val guarded = machine.markUncertain(state.value.layout, panelId)
    if (!persistSafely(guarded)) return PreparedSave.Immediate(saveFailed(panelId,
        DstuError.unavailable("WORKSPACE_LAYOUT_NOT_SAVED", "save guard was not persisted")))
    mutableState.value = state.value.copy(layout = guarded, savingPanelId = panelId, feedback = null)
    record("workspace.save.started", "panelId" to JsonValue.text(panelId.value))
    return PreparedSave.Ready(
        SavePreparation(panel, draft, maxOf(panel.draftRevision, draft?.revision ?: 0)),
    )
}

private suspend fun WorkspaceSession.finishSave(
    prepared: SavePreparation,
    result: PanelSaveResult,
): SaveOutcome {
    val panelId = prepared.panel.id
    val current = state.value.layout.panel(panelId)
    if (current == null) {
        mutableState.value = state.value.copy(savingPanelId = null)
        return SaveOutcome.Failed(
            DstuError.notFound("WORKSPACE_PANEL_MISSING", "workspace panel is not open"),
        )
    }
    if (result is PanelSaveResult.Failed) return rejectedSave(panelId, result.error)
    val metadata = metadata(result)
    if (hasUncommittedDraft(current, prepared.revision, result)) {
        return keepNewerDraft(panelId, metadata)
    }
    val layout = machine.clearUncertain(machine.markSaved(state.value.layout, panelId, metadata.resource, metadata.title), panelId)
    val guarded = machine.updateMetadata(state.value.layout, panelId, metadata.resource, metadata.title)
    if (!persistSafely(guarded)) return uncertainSave(panelId, null)
    val deleteError = deleteSavedDraft(panelId)
    if (deleteError != null) return uncertainSave(panelId, null)
    if (!persistSafely(layout)) return uncertainSave(panelId, null)
    mutableState.value = state.value.copy(layout = layout, savingPanelId = null, feedback = null)
    record("workspace.save.finished", "panelId" to JsonValue.text(panelId.value))
    return if (result is PanelSaveResult.NoChanges) SaveOutcome.NoChanges else SaveOutcome.Saved
}

private fun hasUncommittedDraft(panel: PanelInstance, revision: Long, result: PanelSaveResult): Boolean =
    panel.draftRevision > revision || panel.dirty && result is PanelSaveResult.NoChanges

private suspend fun WorkspaceSession.rejectedSave(panelId: PanelId, error: DstuError): SaveOutcome {
    val cleanGuard = machine.clearUncertain(state.value.layout, panelId)
    if (persistSafely(cleanGuard)) mutableState.value = state.value.copy(layout = cleanGuard)
    return saveFailed(panelId, error)
}

private suspend fun WorkspaceSession.persistSafely(layout: com.deepstudent.features.workspace.api.LayoutState): Boolean =
    try {
        persistOrThrow(layout)
        true
    } catch (_: Exception) {
        false
    }

private suspend fun WorkspaceSession.keepNewerDraft(
    panelId: PanelId,
    metadata: SavedMetadata,
): SaveOutcome {
    val layout = machine.clearUncertain(machine.updateMetadata(
        state.value.layout,
        panelId,
        metadata.resource,
        metadata.title,
    ), panelId)
    mutableState.value = state.value.copy(
        layout = layout,
        savingPanelId = null,
        feedback = WorkspaceFeedback("WORKSPACE_DRAFT_CHANGED", "保存期间草稿有新修改，请再次保存"),
    )
    persist(layout)
    record("workspace.save.newer_draft", "panelId" to JsonValue.text(panelId.value))
    return SaveOutcome.SavedWithNewerDraft
}

private fun metadata(result: PanelSaveResult): SavedMetadata =
    if (result is PanelSaveResult.Saved) {
        SavedMetadata(
            resource = result.resource,
            title = result.title?.takeIf {
                it.length <= PanelInstance.MAX_TITLE_CHARS &&
                    it.none { character -> character.code < 0x20 && character != '\t' }
            },
        )
    } else {
        SavedMetadata(null, null)
    }

private suspend fun WorkspaceSession.deleteSavedDraft(panelId: PanelId): DstuError? =
    try {
        drafts.delete(panelId)
        null
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        failure.asWorkspaceError("WORKSPACE_DRAFT_DELETE_FAILED")
    }

private fun WorkspaceSession.saveFailed(panelId: PanelId, error: DstuError): SaveOutcome {
    mutableState.value = state.value.copy(
        savingPanelId = null,
        feedback = WorkspaceFeedback(error.code, "保存失败，草稿已保留"),
    )
    record(
        "workspace.save.failed",
        "panelId" to JsonValue.text(panelId.value),
        "error" to JsonValue.text(error.code),
    )
    return SaveOutcome.Failed(error)
}

private suspend fun WorkspaceSession.uncertainSave(
    panelId: PanelId,
    failure: Exception?,
): SaveOutcome.Uncertain {
    val sourceError = failure?.asWorkspaceError("WORKSPACE_SAVE_UNCERTAIN")
    val uncertain = DstuError.unavailable(
        "WORKSPACE_SAVE_UNCERTAIN",
        "workspace save result is uncertain",
    )
    val layout = machine.markUncertain(state.value.layout, panelId)
    mutableState.value = state.value.copy(
        layout = layout,
        savingPanelId = null,
        uncertainPanelId = panelId,
        feedback = WorkspaceFeedback("WORKSPACE_SAVE_UNCERTAIN", "保存结果未确认，草稿仍保留"),
    )
    persist(layout)
    record(
        "workspace.save.uncertain",
        "panelId" to JsonValue.text(panelId.value),
        "error" to JsonValue.text(sourceError?.code ?: "WORKSPACE_SAVE_UNCERTAIN"),
    )
    return SaveOutcome.Uncertain(uncertain)
}

internal suspend fun WorkspaceSession.closeWorkspacePanel(panelId: PanelId): CloseResult =
    mutex.withLock {
        requireReady()
        val panel = panelOrThrow(panelId)
        if (panel.saveUncertain) {
            mutableState.value = state.value.copy(pendingClose =
                com.deepstudent.features.workspace.api.PendingClose(panelId, panel.key, true, true))
            return@withLock CloseResult.NeedsDecision
        }
        if (state.value.savingPanelId != null) {
            fail("WORKSPACE_BUSY", "保存进行中，暂不能关闭")
            return@withLock CloseResult.Cancelled
        }
        when (val request = closeGuard.request(panel, state.value.pendingClose)) {
            CloseGuardRequest.Immediate -> {
                if (closeNow(panelId)) CloseResult.Closed else CloseResult.Cancelled
            }
            is CloseGuardRequest.Prompt -> {
                mutableState.value = state.value.copy(pendingClose = request.pending, feedback = null)
                CloseResult.NeedsDecision
            }
            is CloseGuardRequest.Busy -> {
                fail("WORKSPACE_CLOSE_PENDING", "请先处理当前关闭请求")
                CloseResult.Cancelled
            }
        }
    }

internal suspend fun WorkspaceSession.resolveWorkspaceClose(decision: CloseDecision): CloseResult {
    val resolution = mutex.withLock {
        requireReady()
        if (state.value.savingPanelId != null) {
            fail("WORKSPACE_BUSY", "保存进行中，请稍候")
            return@withLock CloseGuardResolution.Invalid
        }
        val pending = state.value.pendingClose ?: return@withLock CloseGuardResolution.Invalid
        closeGuard.resolve(pending, state.value.layout.panel(pending.panelId), decision)
    }
    return when (resolution) {
        CloseGuardResolution.Invalid -> CloseResult.Cancelled
        CloseGuardResolution.Cancel -> cancelPendingClose()
        is CloseGuardResolution.Save -> resolveSaveClose(resolution.pending.panelId)
        is CloseGuardResolution.Discard -> resolveDiscardClose(resolution.pending.panelId)
    }
}

private fun WorkspaceSession.cancelPendingClose(): CloseResult {
    mutableState.value = state.value.copy(pendingClose = null, feedback = null)
    return CloseResult.Cancelled
}

private suspend fun WorkspaceSession.resolveSaveClose(panelId: PanelId): CloseResult =
    when (val saved = saveWorkspacePanel(panelId)) {
        SaveOutcome.Saved, SaveOutcome.NoChanges -> closeAfterSaveIfClean(panelId)
        SaveOutcome.SavedWithNewerDraft -> CloseResult.NeedsDecision
        is SaveOutcome.Failed -> CloseResult.SaveFailed(saved.error)
        is SaveOutcome.Uncertain -> CloseResult.SaveFailed(saved.error)
    }

private suspend fun WorkspaceSession.closeAfterSaveIfClean(panelId: PanelId): CloseResult =
    mutex.withLock {
        val current = state.value.layout.panel(panelId) ?: return@withLock CloseResult.Cancelled
        if (current.dirty) {
            mutableState.value = state.value.copy(
                feedback = WorkspaceFeedback("WORKSPACE_DRAFT_CHANGED", "保存后草稿又有修改，已取消关闭"),
            )
            return@withLock CloseResult.NeedsDecision
        }
        if (closeNow(panelId)) CloseResult.Closed else CloseResult.Cancelled
    }

private suspend fun WorkspaceSession.resolveDiscardClose(panelId: PanelId): CloseResult =
    withContext(NonCancellable) { discardThenClose(panelId) }

private suspend fun WorkspaceSession.discardThenClose(panelId: PanelId): CloseResult {
    val prepared = mutex.withLock { prepareDiscard(panelId) } ?: return CloseResult.Cancelled
    val error = callHostDiscard(prepared.panel, prepared.draft)
    if (error != null) return discardFailed(error)
    return mutex.withLock {
        val current = state.value.layout.panel(panelId) ?: return@withLock CloseResult.Cancelled
        if (current.draftRevision != prepared.revision) {
            mutableState.value = state.value.copy(
                savingPanelId = null,
                feedback = WorkspaceFeedback("WORKSPACE_DRAFT_CHANGED", "丢弃期间草稿有新修改，已取消关闭"),
            )
            return@withLock CloseResult.NeedsDecision
        }
        if (closeNow(panelId)) CloseResult.Closed else CloseResult.Cancelled
    }
}

private suspend fun WorkspaceSession.prepareDiscard(panelId: PanelId): DiscardPreparation? {
    val pending = state.value.pendingClose
    if (pending?.panelId != panelId || state.value.savingPanelId != null) return null
    val panel = state.value.layout.panel(panelId) ?: return null
    val draft = try {
        loadDraftOrNull(panelId)
    } catch (_: Exception) {
        fail("WORKSPACE_DRAFT_READ_FAILED", "本地草稿读取失败")
        return null
    }
    mutableState.value = state.value.copy(savingPanelId = panelId, feedback = null)
    return DiscardPreparation(panel, draft, panel.draftRevision)
}

private fun WorkspaceSession.discardFailed(error: DstuError): CloseResult {
    mutableState.value = state.value.copy(
        savingPanelId = null,
        feedback = WorkspaceFeedback("WORKSPACE_DISCARD_FAILED", "丢弃草稿失败，面板仍保留"),
    )
    return CloseResult.DiscardFailed(error)
}

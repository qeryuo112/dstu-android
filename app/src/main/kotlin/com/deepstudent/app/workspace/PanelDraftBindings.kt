package com.deepstudent.app.workspace

import com.deepstudent.features.notes.api.NoteDraftSnapshot
import com.deepstudent.features.notes.data.NoteDraftCodec
import com.deepstudent.features.notes.ui.NoteDraft
import com.deepstudent.features.notes.ui.NotesViewModel
import com.deepstudent.features.workspace.api.PanelId
import com.deepstudent.features.workspace.api.ResourceKey
import com.deepstudent.features.workspace.api.Workspace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Registration only; the editor VM and workspace remain the respective state authorities. */
internal class PanelDraftBindings(private val workspace: Workspace) {
    private val sources = mutableMapOf<PanelId, NotesViewModel>()
    private val writes = Mutex()

    fun bind(panelId: PanelId, model: NotesViewModel?) {
        if (model == null) sources.remove(panelId) else sources[panelId] = model
    }

    suspend fun flush(panelId: PanelId, requireIdle: Boolean = false) = withContext(NonCancellable) {
        writes.withLock {
            val snapshot = withContext(Dispatchers.Main.immediate) {
                sources[panelId]?.let {
                    if (requireIdle) check(it.state.value.operation == null) { "note operation in progress" }
                    DraftCapture(it.state.value.draft)
                }
            } ?: return@withLock
            withContext(Dispatchers.IO) { persist(panelId, snapshot.draft) }
        }
    }

    private suspend fun persist(panelId: PanelId, draft: NoteDraft?) {
        if (workspace.state.value.layout.panel(panelId) == null) return
        if (draft?.isDirty == true) {
            val snapshot = NoteDraftSnapshot(draft.original, draft.title, draft.content, draft.tags, draft.editorKey)
            workspace.updateDraft(panelId, NoteDraftCodec.encode(snapshot).toByteArray(Charsets.UTF_8))
        } else {
            workspace.clearDraft(panelId)
            draft?.original?.let { workspace.updateResource(panelId, ResourceKey("note", it.id)) }
        }
    }
}

private class DraftCapture(val draft: NoteDraft?)

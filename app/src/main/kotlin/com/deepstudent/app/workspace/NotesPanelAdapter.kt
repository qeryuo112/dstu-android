package com.deepstudent.app.workspace

import com.deepstudent.core.common.error.DstuError
import com.deepstudent.core.common.error.DstuException
import com.deepstudent.core.common.error.DstuErrorKind
import com.deepstudent.features.notes.api.CreateNote
import com.deepstudent.features.notes.api.EditNote
import com.deepstudent.features.notes.api.Notes
import com.deepstudent.features.notes.data.NoteDraftCodec
import com.deepstudent.features.workspace.api.PanelHost
import com.deepstudent.features.workspace.api.PanelSaveContext
import com.deepstudent.features.workspace.api.PanelSaveResult
import com.deepstudent.features.workspace.api.ResourceKey
import com.deepstudent.features.workspace.api.WorkspacePanelKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class NotesPanelAdapter(private val notes: Notes) : PanelHost {
    override val key = WorkspacePanelKeys.Notes
    override val title = "笔记"
    override fun accepts(resource: ResourceKey?): Boolean = resource == null || resource.type == "note"

    override suspend fun save(context: PanelSaveContext): PanelSaveResult = withContext(Dispatchers.IO) {
        val bytes = context.draft?.copyBytes() ?: return@withContext PanelSaveResult.NoChanges
        val draft = NoteDraftCodec.decode(bytes.toString(Charsets.UTF_8))
        val original = draft.original
        val note = try {
            if (original == null) notes.create(CreateNote(draft.title, draft.content, draft.tags))
            else notes.edit(EditNote(original.id, original.updatedAt, draft.title, draft.content, draft.tags))
        } catch (error: DstuException) {
            if (error.error.kind in setOf(DstuErrorKind.VALIDATION, DstuErrorKind.CONFLICT, DstuErrorKind.NOT_FOUND)) {
                return@withContext PanelSaveResult.Failed(error.error)
            }
            throw error
        }
        PanelSaveResult.Saved(ResourceKey("note", note.id), note.title)
    }
}

package com.deepstudent.features.notes.ui

import com.deepstudent.core.common.error.DstuError
import com.deepstudent.core.common.error.DstuException
import com.deepstudent.core.common.id.ResourceId
import com.deepstudent.features.notes.api.CreateNote
import com.deepstudent.features.notes.api.EditNote
import com.deepstudent.features.notes.api.NoteAttachmentLink
import com.deepstudent.features.notes.api.NoteDocument
import com.deepstudent.features.notes.api.NoteMetadata
import com.deepstudent.features.notes.api.NoteTimes
import com.deepstudent.features.notes.api.Notes
import java.util.concurrent.ConcurrentHashMap

internal class FakeNotes : Notes {
    val records = ConcurrentHashMap<String, NoteDocument>()
    var beforeList: suspend () -> Unit = {}
    var beforeGet: suspend () -> Unit = {}
    var beforeWrite: suspend () -> Unit = {}
    var lastEdit: EditNote? = null
    private var revision = 0

    override suspend fun create(command: CreateNote): NoteDocument {
        beforeWrite()
        val id = "note_${++revision}"
        return note(id, command.title, command.content, command.tags, revision.toString()).also { records[id] = it }
    }

    override suspend fun get(id: String, includeDeleted: Boolean): NoteDocument? {
        beforeGet()
        return records[id]?.takeIf { includeDeleted || it.deletedAt == null }
    }

    override suspend fun list(includeDeleted: Boolean): List<NoteDocument> {
        beforeList()
        return records.values.filter { includeDeleted || it.deletedAt == null }.toList()
    }

    override suspend fun edit(command: EditNote): NoteDocument {
        beforeWrite()
        lastEdit = command
        val old = records.getValue(command.id)
        if (old.updatedAt != command.expectedUpdatedAt) throw DstuException(DstuError.conflict("NOTE_CONFLICT", "changed"))
        return note(old.id, command.title, command.content, command.tags, (++revision).toString()).also { records[old.id] = it }
    }

    override suspend fun delete(id: String): NoteDocument = changeDeleted(id, true)
    override suspend fun restore(id: String): NoteDocument = changeDeleted(id, false)

    private suspend fun changeDeleted(id: String, deleted: Boolean): NoteDocument {
        beforeWrite()
        val old = records.getValue(id)
        return note(old.id, old.title, old.content, old.tags, (++revision).toString(), deleted).also { records[id] = it }
    }

    override suspend fun linkAttachment(noteId: String, resourceId: ResourceId): NoteAttachmentLink = error("unused")
    override suspend fun attachmentLinks(noteId: String): List<NoteAttachmentLink> = error("unused")

    fun externalEdit(id: String, content: String) {
        val old = records.getValue(id)
        records[id] = note(id, old.title, content, old.tags, (++revision).toString())
    }
}

internal fun note(
    id: String, title: String, content: String, tags: List<String>, updated: String, deleted: Boolean = false,
): NoteDocument = NoteDocument(
    id, ResourceId.parse("res_$id"), title, content, tags,
    NoteMetadata(false, NoteTimes("created", updated, if (deleted) updated else null), null),
)

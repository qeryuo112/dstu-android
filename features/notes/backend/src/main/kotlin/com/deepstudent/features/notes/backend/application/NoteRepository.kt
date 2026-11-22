package com.deepstudent.features.notes.backend.application

import com.deepstudent.features.notes.api.CreateNote
import com.deepstudent.features.notes.api.EditNote
import com.deepstudent.features.notes.api.NoteDocument
import com.deepstudent.features.notes.api.NoteAttachmentLink
import com.deepstudent.core.common.id.ResourceId

interface NoteRepository {
    suspend fun create(command: CreateNote): NoteDocument
    suspend fun get(id: String, includeDeleted: Boolean): NoteDocument?
    suspend fun list(includeDeleted: Boolean): List<NoteDocument>
    suspend fun edit(command: EditNote): NoteDocument
    suspend fun delete(id: String): NoteDocument
    suspend fun restore(id: String): NoteDocument
    suspend fun linkAttachment(noteId: String, resourceId: ResourceId): NoteAttachmentLink
    suspend fun attachmentLinks(noteId: String): List<NoteAttachmentLink>
}

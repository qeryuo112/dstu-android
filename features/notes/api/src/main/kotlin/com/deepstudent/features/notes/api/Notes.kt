package com.deepstudent.features.notes.api

import com.deepstudent.core.common.id.ResourceId
import com.deepstudent.core.common.json.JsonValue
import java.util.Collections

class NoteDocument(
    val id: String,
    val resourceId: ResourceId,
    val title: String,
    val content: String,
    tags: List<String>,
    val metadata: NoteMetadata,
) {
    val tags: List<String> = Collections.unmodifiableList(ArrayList(tags))
    val isFavorite: Boolean get() = metadata.isFavorite
    val createdAt: String get() = metadata.times.createdAt
    val updatedAt: String get() = metadata.times.updatedAt
    val deletedAt: String? get() = metadata.times.deletedAt
    val props: JsonValue? get() = metadata.props
}

data class NoteTimes(val createdAt: String, val updatedAt: String, val deletedAt: String?)

data class NoteMetadata(val isFavorite: Boolean, val times: NoteTimes, val props: JsonValue?)

data class CreateNote(
    val title: String,
    val content: String,
    val tags: List<String> = emptyList(),
    val folderId: String? = null,
)

data class EditNote(
    val id: String,
    val expectedUpdatedAt: String,
    val title: String,
    val content: String,
    val tags: List<String>,
)

interface Notes {
    suspend fun create(command: CreateNote): NoteDocument
    suspend fun get(id: String, includeDeleted: Boolean = false): NoteDocument?
    suspend fun list(includeDeleted: Boolean = false): List<NoteDocument>
    suspend fun edit(command: EditNote): NoteDocument
    suspend fun delete(id: String): NoteDocument
    suspend fun restore(id: String): NoteDocument
    suspend fun linkAttachment(noteId: String, resourceId: ResourceId): NoteAttachmentLink
    suspend fun attachmentLinks(noteId: String): List<NoteAttachmentLink>
}

data class NoteAttachmentLink(
    val id: String,
    val noteId: String,
    val resourceId: ResourceId,
    val revision: Long,
    val invalidatedAt: String?,
    val targetAvailable: Boolean,
)

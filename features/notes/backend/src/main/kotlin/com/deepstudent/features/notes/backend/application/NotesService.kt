package com.deepstudent.features.notes.backend.application

import com.deepstudent.features.notes.api.CreateNote
import com.deepstudent.features.notes.api.EditNote
import com.deepstudent.features.notes.api.NoteDocument
import com.deepstudent.features.notes.api.NoteAttachmentLink
import com.deepstudent.features.notes.api.Notes
import com.deepstudent.features.notes.backend.domain.NoteRules
import com.deepstudent.core.common.id.ResourceId
import com.deepstudent.core.common.logging.Logger
import com.deepstudent.core.common.logging.NoopLogger
import com.deepstudent.core.common.time.MonotonicClock
import com.deepstudent.core.common.time.SystemMonotonicClock
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.Collections

class NotesService(
    private val repository: NoteRepository,
    logger: Logger = NoopLogger,
    monotonicClock: MonotonicClock = SystemMonotonicClock,
) : Notes {
    private val diagnostics = NoteDiagnostics(logger, monotonicClock)

    override suspend fun create(command: CreateNote): NoteDocument = diagnostics.record("create") {
        currentCoroutineContext().ensureActive()
        NoteRules.validateTitle(command.title)
        NoteRules.validateTags(command.tags)
        command.folderId?.let(NoteRules::validateId)
        repository.create(command.copy(tags = Collections.unmodifiableList(ArrayList(command.tags))))
    }

    override suspend fun get(id: String, includeDeleted: Boolean): NoteDocument? = diagnostics.record("get") {
        NoteRules.validateId(id)
        repository.get(id, includeDeleted)
    }

    override suspend fun list(includeDeleted: Boolean): List<NoteDocument> = diagnostics.record("list") {
        repository.list(includeDeleted)
    }

    override suspend fun edit(command: EditNote): NoteDocument = diagnostics.record("edit") {
        currentCoroutineContext().ensureActive()
        NoteRules.validateId(command.id)
        NoteRules.validateTitle(command.title)
        NoteRules.validateTags(command.tags)
        require(command.expectedUpdatedAt.isNotBlank()) { "expected note revision required" }
        repository.edit(command.copy(tags = Collections.unmodifiableList(ArrayList(command.tags))))
    }

    override suspend fun delete(id: String): NoteDocument = diagnostics.record("delete") {
        currentCoroutineContext().ensureActive()
        NoteRules.validateId(id)
        repository.delete(id)
    }

    override suspend fun restore(id: String): NoteDocument = diagnostics.record("restore") {
        currentCoroutineContext().ensureActive()
        NoteRules.validateId(id)
        repository.restore(id)
    }

    override suspend fun linkAttachment(noteId: String, resourceId: ResourceId): NoteAttachmentLink =
        diagnostics.record("link_attachment") {
            currentCoroutineContext().ensureActive()
            NoteRules.validateId(noteId)
            repository.linkAttachment(noteId, resourceId)
        }

    override suspend fun attachmentLinks(noteId: String): List<NoteAttachmentLink> =
        diagnostics.record("list_attachments") {
            NoteRules.validateId(noteId)
            repository.attachmentLinks(noteId)
        }
}

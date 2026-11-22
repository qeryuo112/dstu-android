package com.deepstudent.features.notes.data

import com.deepstudent.core.common.id.Digests
import com.deepstudent.core.common.id.ResourceId
import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.core.common.time.WallClock
import com.deepstudent.features.notes.api.CreateNote
import com.deepstudent.features.notes.api.EditNote
import com.deepstudent.features.notes.api.NoteDocument
import com.deepstudent.features.notes.api.NoteAttachmentLink
import com.deepstudent.features.notes.backend.application.NoteRepository
import com.deepstudent.features.notes.backend.domain.NoteRules
import com.deepstudent.platform.database.SqlDatabase
import com.deepstudent.platform.database.SqlOperations
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.time.Instant
import java.time.format.DateTimeFormatterBuilder
import java.util.UUID

class SqlNoteRepository(
    private val database: SqlDatabase,
    private val resources: NoteResourceWriter,
    private val clock: WallClock,
) : NoteRepository {
    override suspend fun create(command: CreateNote): NoteDocument {
        val context = currentCoroutineContext()
        return database.transaction {
            context.ensureActive()
            val note = createInTransaction(this, command)
            context.ensureActive()
            note
        }
    }

    internal fun createInTransaction(tx: SqlOperations, command: CreateNote): NoteDocument = with(tx) {
        NoteRules.validateTitle(command.title)
        NoteRules.validateTags(command.tags)
        command.folderId?.let(NoteRules::validateId)
        val now = clock.now()
        val id = newId("note")
        val resource = resources.put(this, NoteResourceInput(newId("res"), id, command.content, now.toEpochMilli()))
        val timestamp = stamp(now)
        execute(
            "INSERT INTO notes (id,resource_id,title,tags,is_favorite,created_at,updated_at) VALUES (?,?,?,?,0,?,?)",
            listOf(text(id), text(resource), text(command.title), text(tagsJson(command.tags)), text(timestamp), text(timestamp)),
        )
        addMembership(this, id, command.folderId, now.toEpochMilli())
        val note = findNote(id, true) ?: storageError("NOTE_INSERT_FAILED")
        NoteHistory.validateLegacy(this, null, command.content)
        NoteHistory.initialize(this, note, timestamp)
        note
    }

    override suspend fun get(id: String, includeDeleted: Boolean): NoteDocument? =
        database.findNote(id, includeDeleted)

    override suspend fun list(includeDeleted: Boolean): List<NoteDocument> {
        val where = if (includeDeleted) "" else " WHERE n.deleted_at IS NULL"
        return database.query(
            "SELECT $NOTE_COLUMNS FROM notes n LEFT JOIN resources r ON r.id=n.resource_id$where ORDER BY n.updated_at DESC,n.id",
            map = ::mapNote,
        )
    }

    override suspend fun edit(command: EditNote): NoteDocument {
        val context = currentCoroutineContext()
        return database.transaction {
            context.ensureActive()
            val old = findNote(command.id, false) ?: noteMissing()
            if (old.updatedAt != command.expectedUpdatedAt) noteConflict()
            NoteHistory.validateLegacy(this, old, command.content)
            val now = nextTimestamp(old.updatedAt, clock.now())
            NoteHistory.snapshot(this, old, "baseline", now)
            val resource = resourceForEdit(this, old, command.content, Instant.parse(now).toEpochMilli())
            execute(
                "UPDATE notes SET resource_id=?,title=?,tags=?,updated_at=? WHERE id=? AND updated_at=? AND deleted_at IS NULL",
                listOf(text(resource), text(command.title), text(tagsJson(command.tags)), text(now), text(old.id), text(old.updatedAt)),
            )
            val updated = findNote(old.id, false) ?: noteMissing()
            if (updated.updatedAt != now) noteConflict()
            migrateRelations(this, old.resourceId.value, resource, now)
            NoteHistory.snapshot(this, updated, "edit", now)
            context.ensureActive()
            updated
        }
    }

    override suspend fun delete(id: String): NoteDocument = setDeleted(id, true)
    override suspend fun restore(id: String): NoteDocument = setDeleted(id, false)

    override suspend fun linkAttachment(noteId: String, resourceId: ResourceId): NoteAttachmentLink {
        val context = currentCoroutineContext()
        return database.transaction {
            context.ensureActive()
            val link = createAttachmentLink(noteId, resourceId, stamp(clock.now()))
            context.ensureActive()
            link
        }
    }

    override suspend fun attachmentLinks(noteId: String): List<NoteAttachmentLink> {
        if (database.findNote(noteId, true) == null) noteMissing()
        return database.attachmentLinks(noteId)
    }

    private suspend fun setDeleted(id: String, deleting: Boolean): NoteDocument {
        val context = currentCoroutineContext()
        return database.transaction {
            context.ensureActive()
            val old = findNote(id, true) ?: noteMissing()
            if ((old.deletedAt != null) == deleting) return@transaction old
            val now = nextTimestamp(old.updatedAt, clock.now())
            if (!deleting) {
                NoteHistory.validateLegacy(this, old, old.content)
                NoteHistory.snapshot(this, old, "baseline", now)
            }
            val title = if (deleting) old.title else restoredTitle(this, old)
            execute(
                "UPDATE notes SET deleted_at=?,title=?,updated_at=? WHERE id=?",
                listOf(nullable(if (deleting) now else null), text(title), text(now), text(id)),
            )
            updateMembership(this, id, deleting, now)
            val updated = findNote(id, true) ?: noteMissing()
            if (!deleting) NoteHistory.snapshot(this, updated, "trash_restore", now)
            context.ensureActive()
            updated
        }
    }

    private fun resourceForEdit(tx: SqlOperations, old: NoteDocument, content: String, now: Long): String {
        val hashes = tx.query("SELECT hash FROM resources WHERE id=?", listOf(text(old.resourceId.value))) { it.text(0) }
        val hash = hashes.singleOrNull() ?: storageError("NOTE_RESOURCE_MISSING")
        if (hash == NoteRules.saltedContentHash(old.id, content) || hash == Digests.sha256HexUtf8(content)) {
            return old.resourceId.value
        }
        return resources.put(tx, NoteResourceInput(newId("res"), old.id, content, now))
    }
}

internal fun newId(prefix: String): String = "${prefix}_${UUID.randomUUID().toString().replace("-", "")}"
internal fun tagsJson(tags: List<String>): String = JsonValue.array(tags.map(JsonValue::text)).literal()
internal fun stamp(instant: Instant): String = DateTimeFormatterBuilder().appendInstant(3).toFormatter().format(instant)

internal fun nextTimestamp(old: String, now: Instant): String {
    val previous = runCatching { Instant.parse(old).toEpochMilli() }.getOrNull()
    val current = now.toEpochMilli()
    val next = if (previous == null) current else maxOf(current, Math.addExact(previous, 1))
    return stamp(Instant.ofEpochMilli(next))
}

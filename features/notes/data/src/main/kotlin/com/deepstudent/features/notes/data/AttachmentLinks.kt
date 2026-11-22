package com.deepstudent.features.notes.data

import com.deepstudent.core.common.error.DstuError
import com.deepstudent.core.common.error.DstuException
import com.deepstudent.core.common.id.ResourceId
import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.features.notes.api.NoteAttachmentLink
import com.deepstudent.platform.database.SqlOperations
import com.deepstudent.platform.database.SqlValue

internal fun SqlOperations.createAttachmentLink(noteId: String, resourceId: ResourceId, now: String): NoteAttachmentLink {
    if (findNote(noteId, false) == null) noteMissing()
    if (!attachmentAvailable(resourceId.value)) {
        throw DstuException(DstuError.notFound("ATTACHMENT_REFERENCE_MISSING", "attachment reference not found"))
    }
    val existing = attachmentLinks(noteId).firstOrNull {
        it.resourceId == resourceId && it.invalidatedAt == null
    }
    if (existing != null) return existing
    val id = newId("rel")
    execute(
        """INSERT INTO note_learning_relations
            (id,note_id,block_id,relation_type,resource_id,locator_json,revision,created_at,updated_at)
            VALUES (?,?,NULL,'source',?,'{"type":"whole"}',1,?,?)""".trimIndent(),
        listOf(text(id), text(noteId), text(resourceId.value), text(now), text(now)),
    )
    return attachmentLinks(noteId).first { it.id == id }
}

internal fun SqlOperations.attachmentLinks(noteId: String): List<NoteAttachmentLink> {
    val rows = query(
        """SELECT id,resource_id,revision,invalidated_at,locator_json FROM note_learning_relations
            WHERE note_id=? AND relation_type='source' AND block_id IS NULL
              AND EXISTS(SELECT 1 FROM files WHERE files.resource_id=note_learning_relations.resource_id)
            ORDER BY id""".trimIndent(),
        listOf(text(noteId)),
    ) { row ->
        LinkRow(row.text(0), row.text(1), row.long(2), if (row.isNull(3)) null else row.text(3), row.text(4))
    }
    return rows.filter { (JsonValue.parse(it.locator) as? JsonValue.Object)?.get("type") == JsonValue.text("whole") }.map {
        NoteAttachmentLink(it.id, noteId, ResourceId.parse(it.resourceId), it.revision, it.invalidatedAt,
            attachmentAvailable(it.resourceId))
    }
}

private fun SqlOperations.attachmentAvailable(resourceId: String): Boolean = query(
    """SELECT 1 FROM resources r JOIN files f ON f.resource_id=r.id
        WHERE r.id=? AND r.deleted_at IS NULL AND f.deleted_at IS NULL AND f.status='active' LIMIT 1""".trimIndent(),
    listOf(SqlValue.Text(resourceId)),
) { it.long(0) }.isNotEmpty()

private data class LinkRow(
    val id: String,
    val resourceId: String,
    val revision: Long,
    val invalidatedAt: String?,
    val locator: String,
)

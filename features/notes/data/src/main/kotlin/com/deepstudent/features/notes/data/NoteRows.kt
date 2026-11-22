package com.deepstudent.features.notes.data

import com.deepstudent.core.common.error.DstuError
import com.deepstudent.core.common.error.DstuException
import com.deepstudent.core.common.id.ResourceId
import com.deepstudent.core.common.id.Digests
import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.features.notes.api.NoteDocument
import com.deepstudent.features.notes.api.NoteMetadata
import com.deepstudent.features.notes.api.NoteTimes
import com.deepstudent.features.notes.backend.domain.NoteRules
import com.deepstudent.platform.database.SqlOperations
import com.deepstudent.platform.database.SqlRow
import com.deepstudent.platform.database.SqlValue

internal val NOTE_COLUMNS = """
    n.id, n.resource_id, n.title, r.data, n.tags, n.is_favorite,
    n.created_at, n.updated_at, n.deleted_at, n.props, r.hash, r.storage_mode, r.deleted_at
""".trimIndent()

internal fun SqlOperations.findNote(id: String, includeDeleted: Boolean): NoteDocument? {
    val active = if (includeDeleted) "" else " AND n.deleted_at IS NULL"
    val rows = query(
        "SELECT $NOTE_COLUMNS FROM notes n LEFT JOIN resources r ON r.id=n.resource_id WHERE n.id=?$active",
        listOf(SqlValue.Text(id)),
        ::mapNote,
    )
    return rows.singleOrNull()
}

internal fun mapNote(row: SqlRow): NoteDocument {
    val content = noteContent(row)
    val parsed = JsonValue.parse(row.text(4)) as? JsonValue.Array ?: storageError("NOTE_TAGS_INVALID")
    val tags = parsed.values.map { (it as? JsonValue.Text)?.value ?: storageError("NOTE_TAGS_INVALID") }
    return NoteDocument(
        id = row.text(0),
        resourceId = ResourceId.parse(row.text(1)),
        title = row.text(2),
        content = content,
        tags = tags,
        metadata = NoteMetadata(
            isFavorite = row.long(5) != 0L,
            times = NoteTimes(row.text(6), row.text(7), if (row.isNull(8)) null else row.text(8)),
            props = if (row.isNull(9)) null else JsonValue.parse(row.text(9)),
        ),
    )
}

private fun noteContent(row: SqlRow): String {
    if (row.isNull(3)) storageError("NOTE_RESOURCE_MISSING")
    if (row.isNull(10) || row.text(11) != "inline" || !row.isNull(12)) storageError("NOTE_RESOURCE_INVALID")
    val content = row.text(3)
    val hash = row.text(10)
    if (hash != NoteRules.saltedContentHash(row.text(0), content) && hash != Digests.sha256HexUtf8(content)) {
        storageError("NOTE_RESOURCE_HASH_MISMATCH")
    }
    return content
}

internal fun NoteDocument.tagsJson(): String = JsonValue.array(tags.map(JsonValue::text)).literal()

internal fun storageError(code: String): Nothing =
    throw DstuException(DstuError.internal(code, "note storage is inconsistent"))

internal fun noteMissing(): Nothing =
    throw DstuException(DstuError.notFound("NOTE_NOT_FOUND", "note not found"))

internal fun noteConflict(): Nothing =
    throw DstuException(DstuError.conflict("NOTE_CONFLICT", "note revision changed"))

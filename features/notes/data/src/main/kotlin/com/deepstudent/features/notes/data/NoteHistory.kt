package com.deepstudent.features.notes.data

import com.deepstudent.core.common.error.DstuError
import com.deepstudent.core.common.error.DstuException
import com.deepstudent.features.notes.api.NoteDocument
import com.deepstudent.features.notes.backend.domain.NoteAssetReferences
import com.deepstudent.platform.database.SqlOperations
import com.deepstudent.platform.database.SqlValue
import java.util.UUID

/** S1.06 writes legacy Markdown only; richer formats remain owned by the editor units. */
internal object NoteHistory {
    private val stableMarker = Regex("(?m)^ {0,3}<!--\\s*ds:")

    fun validateLegacy(transaction: SqlOperations, note: NoteDocument?, content: String) {
        if (hasAdvancedFormat(content) || note?.content?.let(::hasAdvancedFormat) == true) {
            unsupported()
        }
        if (note == null) return
        val formats = transaction.query(
            "SELECT content_format, format_version, serializer_version FROM note_document_formats WHERE note_id=?",
            listOf(SqlValue.Text(note.id)),
        ) { Triple(it.text(0), it.long(1), it.text(2)) }
        if (formats.any { it != Triple("markdown-legacy", 1L, "markdown-v1") }) unsupported()
    }

    private fun hasAdvancedFormat(content: String): Boolean =
        stableMarker.containsMatchIn(content) || Regex("(?m)^ {0,3}:::ds-columns\\b").containsMatchIn(content)

    fun initialize(transaction: SqlOperations, note: NoteDocument, now: String) {
        transaction.execute(
            """INSERT INTO note_document_formats
                (note_id, content_format, format_version, serializer_version, baseline_version_id, updated_at)
                VALUES (?, 'markdown-legacy', 1, 'markdown-v1', NULL, ?)""".trimIndent(),
            listOf(SqlValue.Text(note.id), SqlValue.Text(now)),
        )
        snapshot(transaction, note, "created", now)
    }

    fun snapshot(transaction: SqlOperations, note: NoteDocument, source: String, now: String) {
        val parent = transaction.query(
            "SELECT version_id, title, content_md, tags_json, props_json FROM note_document_revisions " +
                "WHERE note_id=? ORDER BY seq DESC LIMIT 1",
            listOf(SqlValue.Text(note.id)),
        ) { row ->
            PreviousRevision(
                row.text(0), row.text(1), row.text(2), row.text(3), if (row.isNull(4)) null else row.text(4),
            )
        }.singleOrNull()
        val tags = note.tagsJson()
        val props = note.props?.literal()
        if (parent != null && parent.title == note.title && parent.content == note.content &&
            parent.tags == tags && parent.props == props
        ) return
        insert(transaction, note, source, now, parent?.id)
    }

    private fun insert(transaction: SqlOperations, note: NoteDocument, source: String, now: String, parent: String?) {
        val bucket = java.time.Instant.parse(now).epochSecond / 300
        val args = listOf(
            text("nrev_${UUID.randomUUID().toString().replace("-", "")}"), text(note.id), nullable(parent),
            text(note.title), text(note.content), text(note.tagsJson()), nullable(note.props?.literal()),
            text(NoteAssetReferences.json(note.content, note.props)), text(source), text(now), SqlValue.Integer(bucket),
        )
        transaction.execute(
            """INSERT INTO note_document_revisions
                (version_id,note_id,parent_version_id,title,content_md,tags_json,props_json,asset_refs_json,
                 source,created_at,edit_bucket,content_format,format_version,serializer_version)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,'markdown-legacy',1,'markdown-v1')""".trimIndent(), args,
        )
    }

    private fun unsupported(): Nothing =
        throw DstuException(DstuError.conflict("NOTE_FORMAT_UNSUPPORTED", "note format requires another editor"))
}

private data class PreviousRevision(
    val id: String,
    val title: String,
    val content: String,
    val tags: String,
    val props: String?,
)

internal fun text(value: String): SqlValue = SqlValue.Text(value)
internal fun nullable(value: String?): SqlValue = value?.let(SqlValue::Text) ?: SqlValue.Null

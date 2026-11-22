package com.deepstudent.features.notes.data

import com.deepstudent.core.common.error.DstuError
import com.deepstudent.core.common.error.DstuException
import com.deepstudent.features.notes.api.NoteDocument
import com.deepstudent.platform.database.SqlOperations
import com.deepstudent.platform.database.SqlValue

internal fun addMembership(tx: SqlOperations, noteId: String, folderId: String?, now: Long) {
    if (folderId != null) {
        val found = tx.query(
            "SELECT id FROM folders WHERE id=? AND deleted_at IS NULL", listOf(text(folderId)),
        ) { it.text(0) }.isNotEmpty()
        if (!found) throw DstuException(DstuError.notFound("FOLDER_NOT_FOUND", "folder not found"))
    }
    tx.execute(
        "INSERT INTO folder_items (id,folder_id,item_type,item_id,sort_order,created_at,updated_at) VALUES (?,?,'note',?,0,?,?)",
        listOf(text(newId("fi")), nullable(folderId), text(noteId), SqlValue.Integer(now), SqlValue.Integer(now)),
    )
}

internal fun updateMembership(tx: SqlOperations, id: String, deleting: Boolean, now: String) {
    val millis = java.time.Instant.parse(now).toEpochMilli()
    if (deleting) {
        tx.execute(
            "UPDATE folder_items SET deleted_at=?,updated_at=? WHERE item_type='note' AND item_id=? AND deleted_at IS NULL",
            listOf(text(now), SqlValue.Integer(millis), text(id)),
        )
    } else {
        tx.execute(
            """UPDATE folder_items SET deleted_at=NULL,updated_at=? WHERE id=(SELECT id FROM folder_items
                WHERE item_type='note' AND item_id=? AND deleted_at IS NOT NULL
                ORDER BY COALESCE(updated_at,created_at) DESC,created_at DESC LIMIT 1)""".trimIndent(),
            listOf(SqlValue.Integer(millis), text(id)),
        )
    }
}

internal fun restoredTitle(tx: SqlOperations, note: NoteDocument): String {
    for (index in 0 until 1000) {
        val candidate = if (index == 0) note.title else "${note.title} ($index)"
        val occupied = tx.query(
            "SELECT 1 FROM notes WHERE title=? AND deleted_at IS NULL AND id!=? LIMIT 1",
            listOf(text(candidate), text(note.id)),
        ) { it.long(0) }.isNotEmpty()
        if (!occupied) return candidate
    }
    throw DstuException(DstuError.conflict("NOTE_TITLE_CONFLICT", "note title cannot be restored"))
}

internal fun migrateRelations(tx: SqlOperations, previous: String, next: String, now: String) {
    if (previous == next) return
    tx.execute(
        """UPDATE note_learning_relations SET resource_id=?,revision=revision+1,updated_at=?
            WHERE resource_id=? AND invalidated_at IS NULL
            AND NOT EXISTS (SELECT 1 FROM notes WHERE resource_id=?)""".trimIndent(),
        listOf(text(next), text(now), text(previous), text(previous)),
    )
}

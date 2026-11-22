package com.deepstudent.services.resources.data

import com.deepstudent.core.common.id.Digests
import com.deepstudent.core.common.id.ResourceId
import com.deepstudent.platform.database.SqlOperations
import com.deepstudent.platform.database.SqlValue
import com.deepstudent.services.resources.api.InlineNoteResource

/** Resource owner for note text; participates in the caller's VFS transaction. */
class SqlInlineResourceWriter {
    fun put(tx: SqlOperations, input: InlineNoteResource): ResourceId {
        val hash = Digests.sha256HexUtf8("${input.noteId}:${input.content}")
        val existing = tx.resourceByHash(hash)
        if (existing != null) {
            if (existing.type != "note" || existing.data != input.content || existing.sourceId != input.noteId) {
                resourceConflict()
            }
            return ResourceId.parse(existing.id)
        }
        tx.execute(
            """INSERT INTO resources
                (id,hash,type,source_id,source_table,storage_mode,data,ref_count,created_at,updated_at)
                VALUES (?,?,'note',?,'notes','inline',?,0,?,?)""".trimIndent(),
            listOf(
                text(input.resourceId.value), text(hash), text(input.noteId), text(input.content),
                SqlValue.Integer(input.nowMillis), SqlValue.Integer(input.nowMillis),
            ),
        )
        return input.resourceId
    }
}

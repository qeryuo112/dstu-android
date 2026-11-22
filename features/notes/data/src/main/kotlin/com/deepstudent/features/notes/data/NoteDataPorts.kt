package com.deepstudent.features.notes.data

import com.deepstudent.platform.database.SqlOperations

data class NoteResourceInput(
    val resourceId: String,
    val noteId: String,
    val content: String,
    val nowMillis: Long,
)

/** Implemented by the resource owner and invoked with the note's existing transaction. */
fun interface NoteResourceWriter {
    fun put(transaction: SqlOperations, input: NoteResourceInput): String
}

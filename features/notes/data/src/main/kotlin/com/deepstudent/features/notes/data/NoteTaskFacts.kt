package com.deepstudent.features.notes.data

import com.deepstudent.core.common.id.Digests
import com.deepstudent.core.common.id.TaskId
import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.features.notes.api.CreateNote
import com.deepstudent.platform.database.SqlOperations
import java.time.Instant

data class NoteTaskIdentity(val taskId: String, val generation: Long) {
    init {
        TaskId.parse(taskId)
        require(generation > 0) { "invalid task generation" }
    }
}

data class NoteTaskFact(
    val factId: String,
    val identity: NoteTaskIdentity,
    val noteId: String,
    val committedAt: Instant,
)

internal fun requestHash(command: CreateNote): String = Digests.sha256HexUtf8(JsonValue.obj(
    "title" to JsonValue.text(command.title), "content" to JsonValue.text(command.content),
    "tags" to JsonValue.array(command.tags.map(JsonValue::text)),
    "folderId" to (command.folderId?.let(JsonValue::text) ?: JsonValue.Null),
).canonical())

internal fun SqlOperations.taskFact(identity: NoteTaskIdentity): Pair<NoteTaskFact, String>? = query(
    "SELECT fact_id,note_id,committed_at,request_hash FROM __android_notes_task_facts WHERE task_id=? AND generation=?",
    listOf(text(identity.taskId), com.deepstudent.platform.database.SqlValue.Integer(identity.generation)),
) { row ->
    NoteTaskFact(row.text(0), identity, row.text(1), Instant.parse(row.text(2))) to row.text(3)
}.singleOrNull()

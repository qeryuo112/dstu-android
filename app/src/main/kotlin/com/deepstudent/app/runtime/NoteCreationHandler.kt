package com.deepstudent.app.runtime

import com.deepstudent.core.common.json.JsonFields
import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.features.notes.api.CreateNote
import com.deepstudent.features.notes.data.NoteTaskIdentity
import com.deepstudent.features.notes.data.SqlNoteTaskStore
import com.deepstudent.services.runtime.api.RuntimeTaskContext
import com.deepstudent.services.runtime.api.RuntimeTaskHandler

internal class NoteCreationHandler(
    private val store: SqlNoteTaskStore,
    private val beforeWrite: suspend (RuntimeTaskContext) -> Unit = {},
) : RuntimeTaskHandler {
    override suspend fun execute(context: RuntimeTaskContext, payload: JsonValue) {
        val input = payload as? JsonValue.Object ?: error("invalid note task payload")
        val command = CreateNote(JsonFields.string(input, "title"), JsonFields.string(input, "content"))
        if (context.resumeCheckpoint != READY_TO_WRITE) context.checkpoint(READY_TO_WRITE)
        beforeWrite(context)
        store.create(command, NoteTaskIdentity(context.taskId.value, context.generation))
    }

    private companion object {
        val READY_TO_WRITE = JsonValue.obj("stage" to JsonValue.text("writing"))
    }
}

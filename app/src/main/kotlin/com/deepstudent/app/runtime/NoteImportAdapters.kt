package com.deepstudent.app.runtime

import com.deepstudent.app.storage.MainStorageResources
import com.deepstudent.features.notes.api.CreateNote
import com.deepstudent.features.notes.backend.application.NoteImportWriter
import com.deepstudent.features.notes.backend.application.NoteStreamReader
import com.deepstudent.features.notes.data.NoteTaskIdentity
import com.deepstudent.platform.network.NetworkRequest
import com.deepstudent.platform.network.SseEnd
import com.deepstudent.services.runtime.api.RuntimeTaskContext

internal fun MainStorageResources.noteStreamReader(): NoteStreamReader = NoteStreamReader { url, consume ->
    val end = network.streamSse(NetworkRequest(url), onEvent = { consume(it.data) })
    check(end == SseEnd.COMPLETE) { "import stream incomplete" }
}

internal class NoteImportStorageAdapter(private val storage: MainStorageResources) : NoteImportWriter {
    override suspend fun completed(context: RuntimeTaskContext, title: String, content: String) {
        storage.noteTasks.create(CreateNote(title, content), NoteTaskIdentity(context.taskId.value, context.generation))
    }

    override suspend fun cancelled(context: RuntimeTaskContext) {
        storage.noteCancellations.commit(NoteTaskIdentity(context.taskId.value, context.generation))
    }
}

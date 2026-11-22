package com.deepstudent.features.notes.backend.application

import com.deepstudent.core.common.json.JsonFields
import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.services.runtime.api.RuntimeTaskContext
import com.deepstudent.services.runtime.api.RuntimeTaskHandler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

fun interface NoteStreamReader {
    suspend fun read(url: String, consume: suspend (String) -> Unit)
}

interface NoteImportWriter {
    suspend fun completed(context: RuntimeTaskContext, title: String, content: String)
    suspend fun cancelled(context: RuntimeTaskContext)
}

/** Imports streamed text with durable partial output; transport and VFS remain adapters. */
class NoteStreamImport(private val reader: NoteStreamReader, private val writer: NoteImportWriter) : RuntimeTaskHandler {
    override suspend fun execute(context: RuntimeTaskContext, payload: JsonValue) {
        val request = payload as? JsonValue.Object ?: error("invalid import payload")
        val url = JsonFields.string(request, "url")
        val title = JsonFields.string(request, "title")
        val content = StringBuilder()
        try {
            reader.read(url) { chunk ->
                require(chunk.length <= MAX_CHARS - content.length) { "import exceeds text budget" }
                content.append(chunk)
                context.checkpoint(JsonValue.obj("text" to JsonValue.text(content.toString())))
            }
            writer.completed(context, title, content.toString())
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { writer.cancelled(context) }
            throw cancelled
        }
    }

    private companion object {
        const val MAX_CHARS = 1024 * 1024
    }
}

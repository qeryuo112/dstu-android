package com.deepstudent.app.runtime

import com.deepstudent.core.common.id.TaskId
import com.deepstudent.features.notes.data.NoteTaskFact
import com.deepstudent.features.notes.data.NoteTaskIdentity
import com.deepstudent.features.notes.data.SqlNoteTaskStore
import com.deepstudent.features.notes.data.NoteTaskCancellations
import com.deepstudent.services.runtime.api.BusinessCompletion
import com.deepstudent.services.runtime.api.BusinessCompletionSource
import com.deepstudent.services.runtime.api.RuntimeState

/** Only the composition root maps business completion records into the runtime contract. */
internal class NotesCompletionSource(
    private val store: SqlNoteTaskStore,
    private val cancellations: NoteTaskCancellations,
) : BusinessCompletionSource {
    override suspend fun find(taskId: TaskId, generation: Long): BusinessCompletion? {
        val identity = NoteTaskIdentity(taskId.value, generation)
        store.find(identity)?.let { return it.completion() }
        return cancellations.find(identity)?.let {
            BusinessCompletion(it.factId, taskId, generation, RuntimeState.CANCELLED, "cancelled", it.committedAt)
        }
    }

    override suspend fun pending(consumerId: String, limit: Int): List<BusinessCompletion> =
        (store.pending(consumerId, limit).map { it.completion() } + cancellations.pending(consumerId, limit).map {
            BusinessCompletion(it.factId, TaskId.parse(it.identity.taskId), it.identity.generation,
                RuntimeState.CANCELLED, "cancelled", it.committedAt)
        }).sortedBy { it.factId }.take(limit)

    override suspend fun acknowledge(consumerId: String, factId: String) {
        if (factId.startsWith("notes:cancel:")) cancellations.acknowledge(consumerId, factId)
        else store.acknowledge(consumerId, factId)
    }
}

internal fun NoteTaskFact.completion(): BusinessCompletion = BusinessCompletion(
    factId, TaskId.parse(identity.taskId), identity.generation, RuntimeState.COMPLETED, noteId, committedAt,
)

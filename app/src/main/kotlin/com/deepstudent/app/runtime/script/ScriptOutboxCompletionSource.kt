package com.deepstudent.app.runtime.script

import com.deepstudent.core.common.id.TaskId
import com.deepstudent.services.runtime.api.BusinessCompletion
import com.deepstudent.services.runtime.api.BusinessCompletionSource
import com.deepstudent.services.script.backend.ScriptCompletionOutbox
import com.deepstudent.services.script.backend.ScriptOutboxFact
import com.deepstudent.services.script.backend.ScriptReceiptStore
import com.deepstudent.services.script.backend.completion

/**
 * Optional adapter that exposes the opt-in script completion outbox through the runtime
 * contract. It is deliberately independent of the app composition root: [ScriptCompletionSource]
 * over [ScriptReceiptStore] stays the default path, and nothing here is installed by
 * `MainScriptResources` or any Compose graph.
 */
internal class ScriptOutboxCompletionSource(private val outbox: ScriptCompletionOutbox, private val store: ScriptReceiptStore) :
    BusinessCompletionSource {
    override suspend fun find(taskId: TaskId, generation: Long): BusinessCompletion? =
        store.findReceipt(taskId, generation)?.completion()?.runtimeFact()

    override suspend fun pending(consumerId: String, limit: Int): List<BusinessCompletion> =
        outbox.pending(consumerId, limit).map { it.runtimeFact() }

    override suspend fun acknowledge(consumerId: String, factId: String) {
        outbox.acknowledge(consumerId, factId)
    }
}

private fun ScriptOutboxFact.runtimeFact() = BusinessCompletion(
    factId,
    taskId,
    generation,
    state.runtimeState(),
    receipt,
    committedAt,
)

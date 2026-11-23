package com.deepstudent.app.runtime.script

import com.deepstudent.core.common.id.TaskId
import com.deepstudent.services.runtime.api.BusinessCompletion
import com.deepstudent.services.runtime.api.BusinessCompletionSource
import com.deepstudent.services.runtime.api.RuntimeState
import com.deepstudent.services.script.backend.ScriptCompletion
import com.deepstudent.services.script.backend.ScriptReceiptStore
import com.deepstudent.services.script.backend.ScriptTerminalState
import com.deepstudent.services.script.backend.completion

internal class ScriptCompletionSource(private val store: ScriptReceiptStore) : BusinessCompletionSource {
    override suspend fun find(taskId: TaskId, generation: Long): BusinessCompletion? =
        store.findReceipt(taskId, generation)?.completion()?.runtimeFact()
    override suspend fun pending(consumerId: String, limit: Int): List<BusinessCompletion> =
        store.pending(consumerId, limit).map { it.runtimeFact() }
    override suspend fun acknowledge(consumerId: String, factId: String) {
        store.acknowledge(consumerId, factId)
    }
}

internal fun ScriptCompletion.runtimeFact() = BusinessCompletion(factId, taskId, generation, state.runtimeState(), receipt, committedAt)

internal fun ScriptTerminalState.runtimeState(): RuntimeState = when (this) {
    ScriptTerminalState.COMPLETED -> RuntimeState.COMPLETED
    ScriptTerminalState.FAILED -> RuntimeState.FAILED
    ScriptTerminalState.CANCELLED -> RuntimeState.CANCELLED
}

package com.deepstudent.services.runtime.api

import com.deepstudent.core.common.id.TaskId
import java.time.Instant

/** Written by a business owner together with its terminal fact, in that business database transaction. */
data class BusinessCompletion(
    val factId: String,
    val taskId: TaskId,
    val generation: Long,
    val state: RuntimeState,
    val receipt: String,
    val committedAt: Instant,
) {
    init {
        require(factId.isNotBlank() && receipt.isNotBlank()) { "invalid business completion" }
        require(generation > 0) { "invalid generation" }
        require(state == RuntimeState.COMPLETED || state == RuntimeState.FAILED || state == RuntimeState.CANCELLED) {
            "invalid business terminal state"
        }
    }
}

/**
 * Implemented by each business owner; runtime never fabricates these facts.
 * Pending pages contain unacknowledged facts for this consumer. Acknowledgement
 * is idempotent and occurs only after the runtime projection commits. Both the
 * fact id and receipt must remain stable across retries and process restarts;
 * fact ids must be globally unique across registered business sources. Calls
 * must be bounded local database operations, not network operations.
 */
interface BusinessCompletionSource {
    suspend fun find(taskId: TaskId, generation: Long): BusinessCompletion?
    suspend fun pending(consumerId: String, limit: Int): List<BusinessCompletion>
    suspend fun acknowledge(consumerId: String, factId: String)
}

object BusinessCompletionConsumers {
    const val RUNTIME = "runtime.recovery"
}

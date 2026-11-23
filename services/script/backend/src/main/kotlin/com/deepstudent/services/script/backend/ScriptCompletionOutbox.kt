package com.deepstudent.services.script.backend

import com.deepstudent.core.common.id.TaskId
import java.time.Instant

/**
 * One stable outbox entry referencing a script receipt. The receipt table remains the
 * single business authority; this record only carries fact id/sequence for consumers.
 */
data class ScriptOutboxFact(
    val seq: Long,
    val factId: String,
    val taskId: TaskId,
    val generation: Long,
    val state: ScriptTerminalState,
    val receipt: String,
    val committedAt: Instant,
) {
    init {
        require(seq > 0 && factId.isNotBlank() && receipt.isNotBlank()) { "invalid script outbox fact" }
        require(generation > 0) { "invalid script outbox generation" }
    }
}

/**
 * Opt-in completion outbox over the script business database. Entries are appended in the
 * same transaction as the receipt (database trigger) and consumed with a per-consumer cursor
 * that never advances past an unacknowledged fact. It is not wired into the app by default.
 */
interface ScriptCompletionOutbox {
    /** Facts after this consumer's cursor that are not acknowledged, in stable sequence order. */
    suspend fun pending(consumerId: String, limit: Int): List<ScriptOutboxFact>

    /** Idempotent; advances the consumer cursor only over a contiguous acknowledged prefix. */
    suspend fun acknowledge(consumerId: String, factId: String)

    /** Highest contiguous acknowledged sequence for the consumer; zero when nothing was acknowledged. */
    fun consumerCursor(consumerId: String): Long

    /** Enqueues receipts that predate outbox installation. Returns the number of new entries. */
    suspend fun backfill(): Int
}

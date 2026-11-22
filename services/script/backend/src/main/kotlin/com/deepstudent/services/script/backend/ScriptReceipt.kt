package com.deepstudent.services.script.backend

import com.deepstudent.core.common.id.TaskId
import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.services.script.api.ScriptFailureKind
import java.time.Instant

enum class ScriptTerminalState {
    COMPLETED,
    FAILED,
    CANCELLED,
}

data class ScriptFailureRecord(
    val kind: ScriptFailureKind,
    val message: String,
)

data class ScriptReceipt(
    val factId: String,
    val taskId: TaskId,
    val generation: Long,
    val state: ScriptTerminalState,
    val receipt: String,
    val committedAt: Instant,
    val content: ScriptReceiptContent,
) {
    val result: JsonValue? get() = content.result
    val trace: List<ScriptTraceEntry> get() = content.trace
    val failure: ScriptFailureRecord? get() = content.failure
    val durationMillis: Long get() = content.durationMillis
    val callsUsed: Int get() = content.callsUsed
    init {
        require(factId.isNotBlank() && receipt.isNotBlank()) { "invalid script receipt" }
        require(generation > 0 && callsUsed >= 0 && durationMillis >= 0) { "invalid script receipt" }
        require(trace.size == callsUsed) { "script trace and call count differ" }
        require((state == ScriptTerminalState.COMPLETED) == (failure == null)) {
            "completed script receipts cannot carry a failure"
        }
    }
}

data class ScriptReceiptContent(
    val result: JsonValue?,
    val trace: List<ScriptTraceEntry>,
    val failure: ScriptFailureRecord?,
    val durationMillis: Long,
    val callsUsed: Int,
)

data class ScriptCompletion(
    val factId: String,
    val taskId: TaskId,
    val generation: Long,
    val state: ScriptTerminalState,
    val receipt: String,
    val committedAt: Instant,
)

/**
 * The store is a business fact owner. It never writes the runtime database and
 * the caller supplies the already-owned database handle.
 */
interface ScriptReceiptStore {
    suspend fun findReceipt(taskId: TaskId, generation: Long): ScriptReceipt?
    suspend fun commit(receipt: ScriptReceipt)
    suspend fun pending(consumerId: String, limit: Int): List<ScriptCompletion>
    suspend fun acknowledge(consumerId: String, factId: String)
}

fun ScriptReceipt.completion(): ScriptCompletion =
    ScriptCompletion(factId, taskId, generation, state, receipt, committedAt)

fun receiptFactId(taskId: TaskId, generation: Long): String =
    "script-ptc:${taskId.value}:$generation"

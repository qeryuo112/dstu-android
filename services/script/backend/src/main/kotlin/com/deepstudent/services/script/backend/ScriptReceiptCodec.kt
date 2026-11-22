package com.deepstudent.services.script.backend

import com.deepstudent.core.common.id.TaskId
import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.services.script.api.ScriptFailureKind
import java.time.Instant

object ScriptReceiptCodec {
    fun encode(receipt: ScriptReceipt): String = payload(receipt).canonical()

    fun decode(text: String): ScriptReceipt {
        val root = JsonValue.parseObject(text, "script receipt")
        val failure = (root["failure"] as? JsonValue.Object)?.let {
            ScriptFailureRecord(
                ScriptFailureKind.valueOf(text(it["kind"])),
                text(it["message"]),
            )
        }
        return ScriptReceipt(
            factId = text(root["factId"]),
            taskId = TaskId.parse(text(root["taskId"])),
            generation = number(root["generation"]),
            state = ScriptTerminalState.valueOf(text(root["state"])),
            receipt = text(root["receipt"]),
            committedAt = Instant.parse(text(root["committedAt"])),
            content = ScriptReceiptContent(result = if (boolean(root["hasResult"])) root["result"] ?: JsonValue.Null else null,
            trace = (root["trace"] as? JsonValue.Array)?.values.orEmpty().map(::trace),
            failure = failure,
            durationMillis = number(root["durationMillis"]),
            callsUsed = number(root["callsUsed"]).toInt()),
        )
    }

    private fun payload(receipt: ScriptReceipt): JsonValue.Object = JsonValue.Object.Builder()
        .put("factId", receipt.factId)
        .put("taskId", receipt.taskId.value)
        .put("generation", receipt.generation)
        .put("state", receipt.state.name)
        .put("receipt", receipt.receipt)
        .put("committedAt", receipt.committedAt.toString())
        .put("hasResult", receipt.result != null)
        .put("result", receipt.result ?: JsonValue.Null)
        .put("trace", JsonValue.Array(receipt.trace.map(::traceJson)))
        .put("failure", receipt.failure?.let(::failureJson) ?: JsonValue.Null)
        .put("durationMillis", receipt.durationMillis)
        .put("callsUsed", receipt.callsUsed)
        .build()

    private fun traceJson(entry: ScriptTraceEntry): JsonValue = JsonValue.Object.Builder()
        .put("sequence", entry.sequence)
        .put("tool", entry.tool)
        .put("argumentsHash", entry.argumentsHash)
        .put("durationMillis", entry.durationMillis)
        .put("resultBytes", entry.resultBytes)
        .put("ok", entry.ok)
        .put("error", entry.error?.let { JsonValue.text(it) } ?: JsonValue.Null)
        .build()

    private fun failureJson(failure: ScriptFailureRecord): JsonValue = JsonValue.Object.Builder()
        .put("kind", failure.kind.name)
        .put("message", failure.message)
        .build()

    private fun trace(value: JsonValue): ScriptTraceEntry {
        val row = value as? JsonValue.Object ?: error("invalid script trace")
        return ScriptTraceEntry(
            number(row["sequence"]).toInt(),
            text(row["tool"]),
            text(row["argumentsHash"]),
            number(row["durationMillis"]),
            number(row["resultBytes"]).toInt(),
            (row["ok"] as? JsonValue.Bool)?.value ?: error("invalid trace result"),
            (row["error"] as? JsonValue.Text)?.value,
        )
    }

    private fun text(value: JsonValue?): String =
        (value as? JsonValue.Text)?.value ?: error("invalid script receipt text")

    private fun number(value: JsonValue?): Long =
        (value as? JsonValue.Number)?.asLongOrNull() ?: error("invalid script receipt number")

    private fun boolean(value: JsonValue?): Boolean =
        (value as? JsonValue.Bool)?.value ?: error("invalid script receipt boolean")
}

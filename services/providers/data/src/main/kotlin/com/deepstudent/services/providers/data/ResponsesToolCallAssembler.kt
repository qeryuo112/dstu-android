package com.deepstudent.services.providers.data

import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.services.providers.api.ProviderStreamEvent
import java.util.UUID

/**
 * Function-call streaming state machine for the Responses protocol, ported from the
 * frozen desktop adapter methods `_beginStreamingFunctionCall`,
 * `_appendFunctionCallArgumentsDelta`, the `function_call_arguments.done` branch, the
 * `output_item.done` tool path and `convert_response_tool_call`
 * (providers/mod.rs:1131) in
 * `host/plugins/llm-providers/src/providers/openai-responses.ts`
 * (e6b2657fd59014a73f5bbb1d14c2db732cf23e87).
 *
 * Argument deltas accumulate per item id; a final snapshot replaces the accumulated
 * fragments instead of appending to them, and call ids stay deduplicated across the
 * streamed begin, the done frame and the final response sweep. A call id generated
 * here is session-local, exactly like the frozen desktop fallback.
 */
internal class ResponsesToolCallAssembler {
    private val pendingCalls = LinkedHashMap<String, PendingFunctionCall>()
    private val emittedCallIds = mutableSetOf<String>()

    /** True once any tool-call event was emitted; the decoder maps this to the stop reason. */
    var emitted: Boolean = false
        private set

    /** `_beginStreamingFunctionCall`: announces a streamed function-call item once. */
    fun begin(item: JsonValue.Object, outputIndex: Int, events: MutableList<ProviderStreamEvent>) {
        val itemId = item.textAt("id") ?: return
        val callId = item.textAt("call_id") ?: itemId
        val name = item.textAt("name") ?: ""
        val initialArguments = item.textAt("arguments") ?: ""
        val isNew = !pendingCalls.containsKey(itemId)
        pendingCalls[itemId] = PendingFunctionCall(callId, name, outputIndex, initialArguments)
        if (!isNew || callId in emittedCallIds || name.isEmpty()) return
        emitted = true
        events += snapshotEvent(outputIndex, callId, name, initialArguments, argumentsComplete = false)
    }

    /** `_appendFunctionCallArgumentsDelta`: accumulates one argument fragment per item id. */
    fun appendDelta(frame: JsonValue.Object, events: MutableList<ProviderStreamEvent>) {
        val delta = frame.textAt("delta")
        if (delta.isNullOrEmpty()) return
        val itemId = frame.textAt("item_id") ?: frame.textAt("call_id") ?: return
        val pending = pendingCalls[itemId]
        if (pending == null) {
            pendingCalls[itemId] = PendingFunctionCall("", "", frame.indexAt("output_index") ?: 0, delta)
            return
        }
        pending.arguments += delta
        val outputIndex = frame.indexAt("output_index") ?: pending.outputIndex
        val payload = JsonValue.Object.Builder()
            .put("index", outputIndex)
            .put("type", RESPONSES_TYPE_FUNCTION)
            .put("function", JsonValue.Object.Builder().put("arguments", delta).build())
            .build()
        emitted = true
        events += ProviderStreamEvent.ToolCallDelta(outputIndex, null, RESPONSES_TYPE_FUNCTION, null, delta, payload.literal())
    }

    /** `function_call_arguments.done`: final snapshot assembled from the frame or the pending state. */
    fun completeFromFrame(frame: JsonValue.Object, events: MutableList<ProviderStreamEvent>) {
        val itemId = frame.textAt("item_id")
        val pending = itemId?.let(pendingCalls::get)
        if (itemId != null) pendingCalls.remove(itemId)
        val name = frame.textAt("name") ?: pending?.name?.takeIf(String::isNotEmpty) ?: return
        val callId = doneCallId(frame, pending, itemId) ?: return
        val arguments = frame.textAt("arguments") ?: pendingArguments(pending)
        val outputIndex = frame.indexAt("output_index") ?: pending?.outputIndex ?: 0
        emitSnapshot(doneItem(callId, name, arguments), outputIndex, events)
    }

    private fun doneCallId(frame: JsonValue.Object, pending: PendingFunctionCall?, itemId: String?): String? =
        frame.textAt("call_id") ?: pendingCallId(pending) ?: itemId

    private fun pendingCallId(pending: PendingFunctionCall?): String? = pending?.callId?.takeIf(String::isNotEmpty)

    private fun pendingArguments(pending: PendingFunctionCall?): String? = pending?.arguments?.takeIf(String::isNotEmpty)

    private fun doneItem(callId: String, name: String, arguments: String?): JsonValue.Object = JsonValue.Object.Builder()
        .put("type", RESPONSES_TYPE_FUNCTION_CALL)
        .put("call_id", callId)
        .put("name", name)
        .put("arguments", arguments ?: "{}")
        .build()

    /**
     * `output_item.done` tool path: pending bookkeeping always runs (mirroring the
     * frozen adapter), while the snapshot emission waits for the caller's release
     * decision so moderated routes keep their tool calls withheld.
     */
    fun emitItemDone(item: JsonValue.Object, outputIndex: Int, release: Boolean, events: MutableList<ProviderStreamEvent>) {
        val itemId = item.textAt("id")
        val pending = itemId?.let(pendingCalls::get)
        if (itemId != null) pendingCalls.remove(itemId)
        if (!release) return
        val hasArguments = !item.textAt("arguments").isNullOrEmpty()
        val snapshotItem = if (item.textAt("type") != RESPONSES_TYPE_FUNCTION_CALL || hasArguments) {
            item
        } else {
            pending?.takeIf { it.arguments.isNotEmpty() }?.let { item.withArguments(it.arguments) } ?: item
        }
        emitSnapshot(snapshotItem, outputIndex, events)
    }

    /** Final-response sweep: `_emitResponseToolCall` on every output item, deduplicated by call id. */
    fun emitFromResponse(item: JsonValue.Object, outputIndex: Int, events: MutableList<ProviderStreamEvent>) {
        emitSnapshot(item, outputIndex, events)
    }

    /** `convert_response_tool_call` plus the emitted-id deduplication of `_emitResponseToolCall`. */
    private fun emitSnapshot(item: JsonValue.Object, outputIndex: Int, events: MutableList<ProviderStreamEvent>) {
        if (item.textAt("type") != RESPONSES_TYPE_FUNCTION_CALL) return
        val name = item.textAt("name") ?: return
        val arguments = item.textAt("arguments") ?: item.textAt("input") ?: "{}"
        val callId = item.textAt("call_id") ?: item.textAt("id") ?: "resp_call_${UUID.randomUUID()}"
        if (!emittedCallIds.add(callId)) return
        emitted = true
        events += snapshotEvent(outputIndex, callId, name, arguments, argumentsComplete = true)
    }

    private fun snapshotEvent(
        outputIndex: Int,
        callId: String,
        name: String,
        arguments: String,
        argumentsComplete: Boolean,
    ): ProviderStreamEvent.ToolCallDelta = ProviderStreamEvent.ToolCallDelta(
        outputIndex,
        callId,
        RESPONSES_TYPE_FUNCTION,
        name,
        arguments,
        toolCallJson(outputIndex, callId, name, arguments).literal(),
        argumentsComplete,
    )

    /** The wire shape the frozen desktop emits for provider tool calls. */
    private fun toolCallJson(index: Int, id: String, name: String, arguments: String): JsonValue.Object = JsonValue.Object.Builder()
        .put("index", index)
        .put("id", id)
        .put("type", RESPONSES_TYPE_FUNCTION)
        .put("function", JsonValue.Object.Builder().put("name", name).put("arguments", arguments).build())
        .build()

    private fun JsonValue.Object.withArguments(arguments: String): JsonValue.Object {
        val builder = JsonValue.Object.Builder()
        for ((key, value) in asMap()) if (key != "arguments") builder.put(key, value)
        return builder.put("arguments", arguments).build()
    }

    private class PendingFunctionCall(val callId: String, val name: String, val outputIndex: Int, var arguments: String)
}

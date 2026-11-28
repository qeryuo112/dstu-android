package com.deepstudent.services.providers.data

import com.deepstudent.core.common.error.DstuException
import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.platform.network.SseEnd
import com.deepstudent.platform.network.SseEvent
import com.deepstudent.services.providers.api.ProviderFailureCode
import com.deepstudent.services.providers.api.ProviderStopReason
import com.deepstudent.services.providers.api.ProviderStreamEvent
import com.deepstudent.services.providers.api.ProviderStreamResult
import com.deepstudent.services.providers.api.ProviderUsage
import com.deepstudent.services.providers.api.ResponsesMetadataKind

/**
 * Decodes the frozen desktop Responses stream
 * (`host/plugins/llm-providers/src/providers/openai-responses.ts:595-784` at
 * e6b2657fd59014a73f5bbb1d14c2db732cf23e87) into provider stream events. Output
 * extraction, web-search metadata and function-call assembly live in the
 * `ResponsesOutputExtraction`, `ResponsesSearchMetadata` and
 * `ResponsesToolCallAssembler` siblings in this package.
 *
 * The desktop adapter keeps an explicit terminal requirement: only
 * `response.completed`, `response.done` or the unmoderated `[DONE]` marker close a
 * stream successfully. Any other end is reported once by [finish] as a fixed
 * failure, never as a [ProviderStreamEvent.Completed] — completion belongs to the
 * provider port. Text keeps wire order; a function call snapshot replaces the
 * accumulated argument fragments instead of appending to them; reasoning items
 * and web-search items are preserved as replayable metadata.
 *
 * DeepSeek's moderated routes (official host with the web-search/read-url tools or
 * a high safety level) are held back: text, reasoning and function calls are
 * released only by an approved final response, while web-search progress and usage
 * stay visible because they are not model output. A truncated, filtered or failed
 * moderated stream therefore releases no body text at all — this is the documented
 * Android safety boundary; the desktop would still extract text from a
 * `max_output_tokens` truncation, the Android port never releases unreviewed
 * output on a non-terminal end.
 */
class OpenAiResponsesDecoder(private val deepseekModerated: Boolean, private val deepseekWebTools: Boolean) {
    /** True once the stream reached a success or failure end; the transport stops reading. */
    var terminal: Boolean = false
        private set

    private val text = StringBuilder()
    private var usage: ProviderUsage? = null
    private var stopReason = ProviderStopReason.STOP
    private var failure: ProviderFailureCode? = null
    private var sawContentDelta = false
    private var emittedContent = false
    private var sawReasoningDelta = false
    private var emittedReasoning = false
    private var sawReasoningItem = false
    private val emittedReasoningItemIds = mutableSetOf<String>()
    private val toolCalls = ResponsesToolCallAssembler()
    private val searchMetadata = ResponsesSearchMetadata(deepseekWebTools)

    /**
     * Decodes one SSE frame. Failures that must not discard events already produced
     * by the same frame (an incomplete response with partial text, a provider error
     * that also reports usage) are recorded here and thrown by [finish].
     */
    fun accept(frame: SseEvent): List<ProviderStreamEvent> {
        if (terminal) return emptyList()
        val data = frame.data
        if (data.isEmpty()) return emptyList()
        if (data == DONE) return doneMarker()
        val parsed = parse(data) ?: return emptyList()
        val eventType = eventType(parsed, frame.type)
        if (eventType.isEmpty()) return untypedError(parsed)
        val events = mutableListOf<ProviderStreamEvent>()
        dispatch(eventType, parsed, events)
        if (toolCalls.emitted) stopReason = ProviderStopReason.TOOL_CALL
        return events
    }

    private fun doneMarker(): List<ProviderStreamEvent> {
        if (deepseekModerated) block(ProviderFailureCode.STREAM_TRUNCATED) else terminal = true
        return emptyList()
    }

    private fun untypedError(parsed: JsonValue.Object): List<ProviderStreamEvent> {
        val error = parsed["error"]
        if (error != null && error != JsonValue.Null) block(ProviderFailureCode.PROVIDER_ERROR)
        return emptyList()
    }

    /**
     * Ends the exchange. A recorded protocol failure wins; otherwise only an
     * explicit terminal with a clean transport end yields a result.
     */
    fun finish(end: SseEnd): ProviderStreamResult {
        failure?.let { code -> throw responsesFailure(code) }
        when (end) {
            SseEnd.TRUNCATED -> throw responsesFailure(ProviderFailureCode.STREAM_TRUNCATED)
            SseEnd.REJECTED_HTTP_STATUS -> throw responsesFailure(ProviderFailureCode.PROVIDER_ERROR)
            SseEnd.COMPLETE -> Unit
        }
        if (!terminal) throw responsesFailure(ProviderFailureCode.STREAM_TRUNCATED)
        return ProviderStreamResult(text.toString(), usage, stopReason)
    }

    private fun dispatch(eventType: String, frame: JsonValue.Object, events: MutableList<ProviderStreamEvent>) {
        dispatchOutput(eventType, frame, events)
        dispatchLifecycle(eventType, frame, events)
    }

    /** Text, reasoning, item and function-argument events. */
    private fun dispatchOutput(eventType: String, frame: JsonValue.Object, events: MutableList<ProviderStreamEvent>) {
        when (eventType) {
            TEXT_DELTA -> textDelta(frame, events)
            TEXT_DONE -> textDone(frame, events)
            REASONING_TEXT_DELTA, REASONING_SUMMARY_DELTA -> reasoningDelta(frame, events)
            REASONING_TEXT_DONE, REASONING_SUMMARY_DONE -> reasoningDone(frame, events)
            ITEM_ADDED -> itemAdded(frame, events)
            ITEM_DONE -> itemDone(frame, events)
            ARGUMENTS_DELTA, FUNCTION_ARGUMENTS_DELTA -> appendArgumentsDelta(frame, events)
            ARGUMENTS_DONE, FUNCTION_ARGUMENTS_DONE -> completeArguments(frame, events)
        }
    }

    /** Moderated DeepSeek routes stream no tool arguments before the approved response. */
    private fun appendArgumentsDelta(frame: JsonValue.Object, events: MutableList<ProviderStreamEvent>) {
        if (!deepseekModerated) toolCalls.appendDelta(frame, events)
    }

    private fun completeArguments(frame: JsonValue.Object, events: MutableList<ProviderStreamEvent>) {
        if (!deepseekModerated) toolCalls.completeFromFrame(frame, events)
    }

    /** Web-search progress and the terminal/failure family that owns the stream end. */
    private fun dispatchLifecycle(eventType: String, frame: JsonValue.Object, events: MutableList<ProviderStreamEvent>) {
        when (eventType) {
            SEARCH_IN_PROGRESS, SEARCH_SEARCHING -> searchProgress(eventType, frame, events)
            SEARCH_COMPLETED -> searchCompleted(frame, events)
            RESPONSE_COMPLETED, RESPONSE_DONE -> completed(frame, events)
            RESPONSE_INCOMPLETE, RESPONSE_CANCELLED, RESPONSE_CANCELED -> incomplete(frame, events)
            RESPONSE_FAILED, EVENT_ERROR -> failed(frame, events)
        }
    }

    private fun textDelta(frame: JsonValue.Object, events: MutableList<ProviderStreamEvent>) {
        if (deepseekModerated) return
        val delta = frame.textAt("delta")
        if (delta.isNullOrEmpty()) return
        sawContentDelta = true
        emittedContent = true
        appendText(delta, events)
    }

    private fun textDone(frame: JsonValue.Object, events: MutableList<ProviderStreamEvent>) {
        if (deepseekModerated) return
        if (sawContentDelta) return
        val value = frame.textAt("text") ?: frame.textAt("delta")
        if (value.isNullOrEmpty()) return
        emittedContent = true
        appendText(value, events)
    }

    private fun reasoningDelta(frame: JsonValue.Object, events: MutableList<ProviderStreamEvent>) {
        if (deepseekModerated) return
        val value = frame.textAt("delta") ?: frame.textAt("text")
        if (value.isNullOrEmpty()) return
        sawReasoningDelta = true
        emittedReasoning = true
        events += ProviderStreamEvent.ReasoningDelta(value)
    }

    private fun reasoningDone(frame: JsonValue.Object, events: MutableList<ProviderStreamEvent>) {
        if (deepseekModerated) return
        if (sawReasoningDelta) return
        val value = frame.textAt("text") ?: frame.textAt("delta")
        if (value.isNullOrEmpty()) return
        emittedReasoning = true
        events += ProviderStreamEvent.ReasoningDelta(value)
    }

    private fun itemAdded(frame: JsonValue.Object, events: MutableList<ProviderStreamEvent>) {
        val item = frame.objectAt("item") ?: return
        val outputIndex = outputIndex(frame, item)
        if (!deepseekModerated && item.textAt("type") == RESPONSES_TYPE_FUNCTION_CALL) {
            toolCalls.begin(item, outputIndex, events)
        }
        if (item.textAt("type") == RESPONSES_TYPE_WEB_SEARCH_CALL) {
            searchMetadata.emitItem(item, item.textAt("status") ?: RESPONSES_STAGE_IN_PROGRESS, outputIndex, events)
        }
    }

    private fun itemDone(frame: JsonValue.Object, events: MutableList<ProviderStreamEvent>) {
        val item = frame.objectAt("item") ?: return
        val outputIndex = outputIndex(frame, item)
        if (!deepseekModerated) tryEmitReasoningItem(item, allowUnidentified = true, outputIndex, events)
        toolCalls.emitItemDone(item, outputIndex, !deepseekModerated, events)
        if (item.textAt("type") == RESPONSES_TYPE_WEB_SEARCH_CALL) {
            searchMetadata.emitItem(item, item.textAt("status") ?: RESPONSES_STAGE_COMPLETED, outputIndex, events)
        }
    }

    private fun searchProgress(eventType: String, frame: JsonValue.Object, events: MutableList<ProviderStreamEvent>) {
        val defaultStage = if (eventType.endsWith(IN_PROGRESS_SUFFIX)) RESPONSES_STAGE_IN_PROGRESS else RESPONSES_STAGE_SEARCHING
        searchMetadata.emitProgress(frame, defaultStage, events)
    }

    private fun searchCompleted(frame: JsonValue.Object, events: MutableList<ProviderStreamEvent>) {
        val item = frame.objectAt("item") ?: frame
        searchMetadata.emitItem(item, RESPONSES_STAGE_COMPLETED, frame.indexAt("output_index"), events)
    }

    private fun completed(frame: JsonValue.Object, events: MutableList<ProviderStreamEvent>) {
        val response = frame.objectAt("response") ?: frame
        if (deepseekModerated && blockedByModeration(response, events)) return
        if (!emittedContent) {
            val content = if (deepseekWebTools) deepseekCitedText(response) else extractOutputText(response)
            if (content != null) {
                emittedContent = true
                appendText(content, events)
            }
        }
        if (!emittedReasoning) {
            val reasoning = extractReasoningText(response)
            if (!reasoning.isNullOrEmpty()) {
                emittedReasoning = true
                events += ProviderStreamEvent.ReasoningDelta(reasoning)
            }
        }
        emitUsage(response["usage"], events)
        emitReasoningAndToolCalls(response, events)
        searchMetadata.emitFromResponse(response, events)
        terminal = true
    }

    private fun incomplete(frame: JsonValue.Object, events: MutableList<ProviderStreamEvent>) {
        val response = frame.objectAt("response")
        if (response != null) {
            if (deepseekModerated) {
                // Android safety boundary: no unreviewed DeepSeek text on a non-terminal end.
                if (blockedByModeration(response, events)) return
            } else {
                releasePartialOutput(response, events)
            }
            searchMetadata.emitFromResponse(response, events)
            emitUsage(response["usage"], events)
        }
        block(ProviderFailureCode.STREAM_TRUNCATED)
    }

    /** Desktop release path for an unmoderated `response.incomplete`. */
    private fun releasePartialOutput(response: JsonValue.Object, events: MutableList<ProviderStreamEvent>) {
        if (!emittedContent) {
            val content = if (deepseekWebTools) deepseekCitedText(response) else extractOutputText(response)
            if (content != null) {
                emittedContent = true
                appendText(content, events)
            }
        }
        emitReasoningItems(response, events)
    }

    private fun failed(frame: JsonValue.Object, events: MutableList<ProviderStreamEvent>) {
        frame.objectAt("response")?.let { emitUsage(it["usage"], events) }
        block(ProviderFailureCode.PROVIDER_ERROR)
    }

    /** Emits the DeepSeek moderation block once and records its fixed failure. */
    private fun blockedByModeration(response: JsonValue.Object, events: MutableList<ProviderStreamEvent>): Boolean {
        val refusal = deepseekRefusal(response)
        if (refusal == null && response.objectAt("incomplete_details")?.textAt("reason") != CONTENT_FILTER) return false
        emitUsage(response["usage"], events)
        block(ProviderFailureCode.PROVIDER_ERROR)
        return true
    }

    private fun tryEmitReasoningItem(
        item: JsonValue.Object,
        allowUnidentified: Boolean,
        outputIndex: Int?,
        events: MutableList<ProviderStreamEvent>,
    ) {
        if (item.textAt("type") != RESPONSES_TYPE_REASONING) return
        val id = item.textAt("id")
        if (id != null) {
            if (!emittedReasoningItemIds.add(id)) return
        } else if (!allowUnidentified) {
            return
        }
        sawReasoningItem = true
        events += reasoningMetadata(item, outputIndex)
    }

    private fun emitReasoningItems(response: JsonValue.Object, events: MutableList<ProviderStreamEvent>) {
        val allowUnidentified = !sawReasoningItem
        response.arrayAt("output")?.values?.forEachIndexed { index, raw ->
            val item = raw as? JsonValue.Object ?: return@forEachIndexed
            tryEmitReasoningItem(item, allowUnidentified, index, events)
        }
    }

    private fun emitReasoningAndToolCalls(response: JsonValue.Object, events: MutableList<ProviderStreamEvent>) {
        val allowUnidentified = !sawReasoningItem
        response.arrayAt("output")?.values?.forEachIndexed { index, raw ->
            val item = raw as? JsonValue.Object ?: return@forEachIndexed
            tryEmitReasoningItem(item, allowUnidentified, index, events)
            toolCalls.emitFromResponse(item, index, events)
        }
    }

    private fun emitUsage(raw: JsonValue?, events: MutableList<ProviderStreamEvent>) {
        val parsed = responsesUsage(raw) ?: return
        usage = parsed
        events += ProviderStreamEvent.UsageReported(parsed)
    }

    private fun appendText(value: String, events: MutableList<ProviderStreamEvent>) {
        text.append(value)
        events += ProviderStreamEvent.TextDelta(value)
    }

    /** The output index travels with replayable metadata for ordered history passback. */
    private fun reasoningMetadata(item: JsonValue.Object, outputIndex: Int?) =
        ProviderStreamEvent.ResponsesMetadata(ResponsesMetadataKind.REASONING_ITEM, item.literal(), outputIndex, null)

    private fun outputIndex(frame: JsonValue.Object, item: JsonValue.Object): Int =
        frame.indexAt("output_index") ?: item.indexAt("index") ?: 0

    private fun block(code: ProviderFailureCode) {
        failure = code
        terminal = true
    }

    /** `type` comes from the JSON frame; the SSE event name only fills a missing type. */
    private fun eventType(frame: JsonValue.Object, frameType: String): String {
        val declared = frame["type"]
        if (declared is JsonValue.Text) return declared.value
        if (declared != null || frame.containsKey("type")) return ""
        return frameType.takeIf { it.isNotEmpty() && it != MESSAGE_EVENT } ?: ""
    }

    private fun parse(data: String): JsonValue.Object? = try {
        JsonValue.parse(data) as? JsonValue.Object
    } catch (_: DstuException) {
        null
    }

    private companion object {
        const val DONE = "[DONE]"
        const val MESSAGE_EVENT = "message"
        const val TEXT_DELTA = "response.output_text.delta"
        const val TEXT_DONE = "response.output_text.done"
        const val REASONING_TEXT_DELTA = "response.reasoning_text.delta"
        const val REASONING_SUMMARY_DELTA = "response.reasoning_summary_text.delta"
        const val REASONING_TEXT_DONE = "response.reasoning_text.done"
        const val REASONING_SUMMARY_DONE = "response.reasoning_summary_text.done"
        const val ITEM_ADDED = "response.output_item.added"
        const val ITEM_DONE = "response.output_item.done"
        const val SEARCH_IN_PROGRESS = "response.web_search_call.in_progress"
        const val SEARCH_SEARCHING = "response.web_search_call.searching"
        const val SEARCH_COMPLETED = "response.web_search_call.completed"
        const val ARGUMENTS_DELTA = "response.function_call_arguments.delta"
        const val FUNCTION_ARGUMENTS_DELTA = "response.function_call.arguments.delta"
        const val ARGUMENTS_DONE = "response.function_call_arguments.done"
        const val FUNCTION_ARGUMENTS_DONE = "response.function_call.arguments.done"
        const val RESPONSE_COMPLETED = "response.completed"
        const val RESPONSE_DONE = "response.done"
        const val RESPONSE_INCOMPLETE = "response.incomplete"
        const val RESPONSE_CANCELLED = "response.cancelled"
        const val RESPONSE_CANCELED = "response.canceled"
        const val RESPONSE_FAILED = "response.failed"
        const val EVENT_ERROR = "error"
        const val IN_PROGRESS_SUFFIX = ".in_progress"
        const val CONTENT_FILTER = "content_filter"
    }
}

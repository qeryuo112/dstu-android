package com.deepstudent.services.providers.data

import com.deepstudent.core.common.error.DstuException
import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.platform.network.SseEnd
import com.deepstudent.platform.network.SseEvent
import com.deepstudent.services.providers.api.ProviderEventSink
import com.deepstudent.services.providers.api.ProviderException
import com.deepstudent.services.providers.api.ProviderFailure
import com.deepstudent.services.providers.api.ProviderFailureCode
import com.deepstudent.services.providers.api.ProviderStopReason
import com.deepstudent.services.providers.api.ProviderStreamEvent
import com.deepstudent.services.providers.api.ProviderStreamResult
import com.deepstudent.services.providers.api.ProviderUsage

/**
 * Decodes the frozen desktop Anthropic stream
 * (`providers/anthropic.ts` AnthropicAdapter.parse_stream at
 * e6b2657fd59014a73f5bbb1d14c2db732cf23e87) into provider stream events.
 *
 * Completion is explicit only: `message_stop`, the `[DONE]` marker, or a
 * stream error close the exchange; a clean transport EOF without either is a
 * truncation. A tool call is delivered once its block stopped with a known
 * id/name and verified JSON arguments — missing identity, a missing block
 * stop, or malformed/truncated `input_json_delta` never becomes an executable
 * call (the desktop would fall back to `{}`; this port fails instead).
 * Refusal, safety and context-window stop reasons and in-stream `error`
 * events end the exchange as fixed failures, never as completions.
 *
 * Thinking signatures accumulate per block index and ride the dedicated
 * replay sidecar, never the shared sealed event API.
 */
internal class AnthropicStreamDecoder {
    /** True once the stream reached a success or failure end; the transport stops reading. */
    var terminal: Boolean = false
        private set

    private val text = StringBuilder()
    private var usage: ProviderUsage? = null
    private var usageFromStart: JsonValue? = null
    private var failure: ProviderFailureCode? = null
    private var toolSeen = false
    private var lastStopReason: String? = null
    private val pendingTools = mutableMapOf<Int, PendingTool>()
    private val pendingSignatures = mutableMapOf<Int, StringBuilder>()
    private val pendingThinking = mutableMapOf<Int, StringBuilder>()
    private val replayBlocks = mutableListOf<JsonValue>()

    private class PendingTool(val id: String, val name: String, val hasBaseInput: Boolean, val baseInput: JsonValue?) {
        val buffer = StringBuilder()
    }

    /** Decodes one platform SSE frame (the transport already joined `data:` lines). */
    suspend fun accept(frame: SseEvent, events: ProviderEventSink) {
        acceptBlock(frame.data, events)
    }

    /**
     * Decodes one raw event block with the frozen payload extraction: `data:`
     * lines join with newlines; bare `[DONE]`/JSON blocks are the fallback.
     * Like the frozen parse_stream, late frames after a terminal are still
     * decoded; [terminal] only tells the transport to stop reading.
     */
    suspend fun acceptBlock(block: String, events: ProviderEventSink) {
        val payload = dataPayload(block) ?: return
        val trimmed = rustTrim(payload)
        if (trimmed.isEmpty()) return
        if (trimmed == DONE_MARKER) {
            stopMessage()
            return
        }
        val parsed = parseObject(trimmed) ?: return
        dispatch(parsed, events)
    }

    /**
     * Ends the exchange. A recorded protocol failure wins; otherwise only an
     * explicit terminal with a clean transport end yields a result.
     */
    fun finish(end: SseEnd): ProviderStreamResult {
        failure?.let { code -> throw ProviderException(ProviderFailure(code)) }
        if (end != SseEnd.COMPLETE) throw ProviderException(ProviderFailure(ProviderFailureCode.STREAM_TRUNCATED))
        if (!terminal) throw ProviderException(ProviderFailure(ProviderFailureCode.STREAM_TRUNCATED))
        return ProviderStreamResult(text.toString(), usage, stopReason())
    }

    /** Thinking blocks captured for the dedicated same-target replay sidecar. */
    fun replayThinkingBlocks(): List<JsonValue> = replayBlocks.toList()

    private fun stopReason(): ProviderStopReason = when {
        toolSeen -> ProviderStopReason.TOOL_CALL
        lastStopReason == "max_tokens" -> ProviderStopReason.LENGTH
        else -> ProviderStopReason.STOP
    }

    private suspend fun dispatch(parsed: JsonValue.Object, events: ProviderEventSink) {
        when (ResponsesJson.text(parsed["type"]).orEmpty()) {
            EVENT_CONTENT_BLOCK_DELTA -> contentBlockDelta(parsed, events)
            EVENT_CONTENT_BLOCK_START -> contentBlockStart(parsed)
            EVENT_CONTENT_BLOCK_STOP -> contentBlockStop(parsed, events)
            EVENT_MESSAGE_START -> messageStart(parsed, events)
            EVENT_MESSAGE_DELTA -> messageDelta(parsed, events)
            EVENT_ERROR -> blockStream(ProviderFailureCode.PROVIDER_ERROR)
            EVENT_MESSAGE_STOP -> stopMessage()
        }
    }

    private fun stopMessage() {
        if (pendingTools.isNotEmpty()) blockStream(ProviderFailureCode.STREAM_TRUNCATED)
        pendingTools.clear()
        pendingSignatures.clear()
        pendingThinking.clear()
        usageFromStart = null
        terminal = true
    }

    private suspend fun contentBlockDelta(parsed: JsonValue.Object, events: ProviderEventSink) {
        val delta = ResponsesJson.obj(parsed["delta"]) ?: return
        when (ResponsesJson.text(delta["type"])) {
            "thinking_delta" -> {
                val value = ResponsesJson.text(delta["thinking"])
                if (!value.isNullOrEmpty()) {
                    pendingThinking[parsed.indexAt("index") ?: 0]?.append(value)
                    events.emit(ProviderStreamEvent.ReasoningDelta(value))
                }
            }

            "signature_delta" -> ResponsesJson.text(delta["signature"])?.let { fragment ->
                pendingSignatures.getOrPut(parsed.indexAt("index") ?: 0) { StringBuilder() }.append(fragment)
            }

            else -> appendTextOrArguments(parsed, delta, events)
        }
    }

    /** Any string `text` field ends the branch; only without it is `input_json_delta` read. */
    private suspend fun appendTextOrArguments(parsed: JsonValue.Object, delta: JsonValue.Object, events: ProviderEventSink) {
        val textValue = ResponsesJson.text(delta["text"])
        if (textValue != null) {
            if (textValue.isNotEmpty()) {
                text.append(textValue)
                events.emit(ProviderStreamEvent.TextDelta(textValue))
            }
            return
        }
        if (ResponsesJson.text(delta["type"]) != "input_json_delta") return
        val fragment = ResponsesJson.text(delta["partial_json"])
        val pending = pendingTools[parsed.indexAt("index") ?: 0]
        if (fragment != null && pending != null) pending.buffer.append(fragment)
    }

    private fun contentBlockStart(parsed: JsonValue.Object) {
        val block = ResponsesJson.obj(parsed["content_block"]) ?: return
        val index = parsed.indexAt("index") ?: 0
        when (ResponsesJson.text(block["type"])) {
            "thinking" -> pendingThinking[index] = StringBuilder(ResponsesJson.text(block["thinking"]).orEmpty())

            "redacted_thinking" -> if (block["data"] is JsonValue.Text) replayBlocks += block

            "tool_use" -> pendingTools[index] = PendingTool(
                ResponsesJson.text(block["id"]).orEmpty(),
                ResponsesJson.text(block["name"]).orEmpty(),
                block.containsKey("input"),
                block["input"],
            )
        }
    }

    private suspend fun contentBlockStop(parsed: JsonValue.Object, events: ProviderEventSink) {
        val index = parsed.indexAt("index") ?: 0
        val signature = pendingSignatures.remove(index)?.toString().orEmpty()
        pendingThinking.remove(index)?.let { thinking ->
            replayBlocks += replayThinkingBlock(thinking.toString(), signature)
        }
        completeToolCall(index, events)
    }

    private fun replayThinkingBlock(thinking: String, signature: String): JsonValue.Object {
        val block = AnthropicBlock()
        block["type"] = JsonValue.text("thinking")
        block["thinking"] = JsonValue.text(thinking)
        if (signature.isNotEmpty()) block["signature"] = JsonValue.text(signature)
        return block.freeze()
    }

    private suspend fun completeToolCall(index: Int, events: ProviderEventSink) {
        val pending = pendingTools.remove(index) ?: return
        if (pending.id.isEmpty() || pending.name.isEmpty()) {
            blockStream(ProviderFailureCode.PROVIDER_ERROR)
            return
        }
        val arguments = verifiedArguments(pending)
        if (arguments == null) {
            blockStream(ProviderFailureCode.PROVIDER_ERROR)
            return
        }
        toolSeen = true
        events.emit(toolCallEvent(index, pending, arguments))
    }

    /** Blank buffers fall back to the start-block input; anything else must parse. */
    private fun verifiedArguments(pending: PendingTool): JsonValue? {
        if (rustTrim(pending.buffer.toString()).isEmpty()) {
            return if (pending.hasBaseInput) pending.baseInput else null
        }
        return parseAny(pending.buffer.toString())
    }

    private fun toolCallEvent(index: Int, pending: PendingTool, arguments: JsonValue): ProviderStreamEvent.ToolCallDelta {
        val call = AnthropicBlock()
        call["id"] = JsonValue.text(pending.id)
        call["type"] = JsonValue.text("function")
        call["function"] = JsonValue.obj(
            "name" to JsonValue.text(restoreAnthropicToolName(pending.name)),
            "arguments" to JsonValue.text(arguments.literal()),
        )
        call["index"] = JsonValue.integer(index.toLong())
        return ProviderStreamEvent.ToolCallDelta(
            index = index,
            id = pending.id,
            type = "function",
            name = pending.name,
            argumentsFragment = arguments.literal(),
            argumentsComplete = true,
            rawJson = call.freeze().literal(),
        )
    }

    private suspend fun messageStart(parsed: JsonValue.Object, events: ProviderEventSink) {
        val usage = ResponsesJson.field(ResponsesJson.field(parsed, "message"), "usage") ?: return
        usageFromStart = usage
        emitUsage(AnthropicUsage.build(usage), events)
    }

    private suspend fun messageDelta(parsed: JsonValue.Object, events: ProviderEventSink) {
        val delta = ResponsesJson.obj(parsed["delta"])
        if (delta != null) {
            delta["usage"]?.let { emitUsage(AnthropicUsage.merged(it, usageFromStart), events) }
            val stopReason = ResponsesJson.text(delta["stop_reason"])
            if (stopReason != null) lastStopReason = stopReason
            when (stopReason) {
                "safety", "refusal" -> blockStream(ProviderFailureCode.PROVIDER_ERROR)
                "model_context_window_exceeded" -> blockStream(ProviderFailureCode.STREAM_TRUNCATED)
            }
        }
        parsed["usage"]?.let { emitUsage(AnthropicUsage.merged(it, usageFromStart), events) }
    }

    private suspend fun emitUsage(value: ProviderUsage, events: ProviderEventSink) {
        if (!value.known) return
        usage = value
        events.emit(ProviderStreamEvent.UsageReported(value))
    }

    private fun blockStream(code: ProviderFailureCode) {
        failure = code
        terminal = true
    }

    /** `data:` lines join with newlines; bare `[DONE]`/JSON is the only fallback. */
    private fun dataPayload(block: String): String? {
        val data = block.split('\n')
            .map { it.trimEnd('\r') }
            .filter { it.startsWith("data:") }
            .map { it.substring(5).removePrefix(" ") }
        if (data.isNotEmpty()) return data.joinToString("\n")
        val bare = rustTrim(block)
        if (bare == DONE_MARKER) return bare
        return if (parseAny(bare) != null) bare else null
    }

    private fun parseObject(text: String): JsonValue.Object? = try {
        JsonValue.parse(text) as? JsonValue.Object
    } catch (_: DstuException) {
        null
    }

    private fun parseAny(text: String): JsonValue? = try {
        JsonValue.parse(text)
    } catch (_: DstuException) {
        null
    }

    private fun rustTrim(value: String): String = value.trim { it.isRustWhitespace() }

    private fun Char.isRustWhitespace(): Boolean = when (this) {
        '\u0009', '\u000A', '\u000B', '\u000C', '\u000D', '\u0020', '\u0085', '\u00A0', '\u1680' -> true
        in '\u2000'..'\u200A' -> true
        '\u2028', '\u2029', '\u202F', '\u205F', '\u3000' -> true
        else -> false
    }

    private companion object {
        const val DONE_MARKER = "[DONE]"
        const val EVENT_CONTENT_BLOCK_DELTA = "content_block_delta"
        const val EVENT_CONTENT_BLOCK_START = "content_block_start"
        const val EVENT_CONTENT_BLOCK_STOP = "content_block_stop"
        const val EVENT_MESSAGE_START = "message_start"
        const val EVENT_MESSAGE_DELTA = "message_delta"
        const val EVENT_MESSAGE_STOP = "message_stop"
        const val EVENT_ERROR = "error"
    }
}

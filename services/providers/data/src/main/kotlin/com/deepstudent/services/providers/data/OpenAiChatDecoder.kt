package com.deepstudent.services.providers.data

import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.services.providers.api.ProviderException
import com.deepstudent.services.providers.api.ProviderFailure
import com.deepstudent.services.providers.api.ProviderFailureCode
import com.deepstudent.services.providers.api.ProviderUsage
import java.util.Locale

/**
 * Decodes provider payloads for the frozen Chat Completions stream contract.
 *
 * The desktop adapter emits every text fragment exactly as received. It does
 * not treat a later frame as a cumulative replacement, because ordinary
 * continuations are often prefixes/suffixes of earlier text. Content arrays are
 * therefore expanded in wire order, not accumulated across frames.
 *
 * A [DONE] marker is the only transport terminator decoded here. HTTP status,
 * SSE framing, EOF classification and cancellation stay with the provider port.
 */
class OpenAiChatDecoder {
    private var finished = false
    private var frameHasPayload = false
    private var allChoicesFinished = false

    fun decode(data: String): List<OpenAiDecodeEvent> {
        if (finished) throw failure()
        frameHasPayload = false
        allChoicesFinished = false
        if (data.isEmpty()) return emptyList()
        if (data.trim() == DONE) {
            finished = true
            return listOf(OpenAiDecodeEvent.Done)
        }
        val frame = runCatching { JsonValue.parseObject(data) }.getOrElse { throw failure() }
        frame.error()?.let { return listOf(it) }
        val choices = frame["choices"] as? JsonValue.Array
        val events = choiceEvents(frame) + usageEvents(frame)
        frameHasPayload = events.isNotEmpty()
        allChoicesFinished = choices != null && choices.values.isNotEmpty() && choices.values.all {
            (it as? JsonValue.Object)?.text("finish_reason")?.trim()?.isNotEmpty() == true
        }
        return events
    }

    /** Distinguishes a parsed frame with no meaningful payload from an empty SSE keepalive. */
    fun frameHasPayload(): Boolean = frameHasPayload

    /** True only when this frame contains choices and every one has a nonempty finish_reason. */
    fun allChoicesFinished(): Boolean = allChoicesFinished

    private fun choiceEvents(frame: JsonValue.Object): List<OpenAiDecodeEvent> {
        val choices = frame["choices"] ?: return emptyList()
        val array = choices as? JsonValue.Array ?: throw failure()
        val events = mutableListOf<OpenAiDecodeEvent>()
        array.values.forEach { raw ->
            val choice = raw as? JsonValue.Object ?: throw failure()
            addDelta(choice, events)
            val finish = choice.text("finish_reason")?.trim().orEmpty()
            if (finish.isNotEmpty()) events += OpenAiDecodeEvent.FinishReason(finish)
        }
        return events
    }

    private fun addDelta(choice: JsonValue.Object, events: MutableList<OpenAiDecodeEvent>) {
        val delta = choice["delta"] as? JsonValue.Object ?: return
        addContent(delta["content"], events)
        addReasoning(delta, events)
        addToolCalls(delta["tool_calls"], events)
    }

    private fun addContent(content: JsonValue?, events: MutableList<OpenAiDecodeEvent>) {
        when (content) {
            is JsonValue.Text -> content.value.takeIf(String::isNotEmpty)?.let { events += OpenAiDecodeEvent.TextDelta(it) }
            is JsonValue.Array -> addContentParts(content.values, events)
            null, JsonValue.Null -> Unit
            else -> throw failure()
        }
    }

    private fun addReasoning(delta: JsonValue.Object, events: MutableList<OpenAiDecodeEvent>) {
        // Desktop marks reasoning_content as seen only when it is a string.
        // An empty string therefore suppresses fallback, while null/mistyped
        // values let reasoning or reasoning_details contribute normally.
        val reasoningContent = delta["reasoning_content"] as? JsonValue.Text
        val reasoning = delta["reasoning"] as? JsonValue.Text
        when {
            reasoningContent != null -> events += OpenAiDecodeEvent.ReasoningDelta(reasoningContent.value)
            !reasoning?.value.isNullOrEmpty() -> events += OpenAiDecodeEvent.ReasoningDelta(reasoning.value)
            else -> addReasoningDetails(delta["reasoning_details"], events)
        }
    }

    private fun addToolCalls(toolCalls: JsonValue?, events: MutableList<OpenAiDecodeEvent>) {
        if (toolCalls == null || toolCalls == JsonValue.Null) return
        val array = toolCalls as? JsonValue.Array ?: throw failure()
        array.values.forEach { raw ->
            val call = raw as? JsonValue.Object ?: throw failure()
            toolDelta(call)?.let { events += OpenAiDecodeEvent.ToolCallDelta(it) }
        }
    }

    private fun toolDelta(call: JsonValue.Object): OpenAiToolCallDelta? {
        val index = call.toolIndex() ?: return null
        val function = call["function"] as? JsonValue.Object
        val id = call.text("id")
        val name = function?.text("name")
        val arguments = argumentsText(function?.get("arguments"))
        if (id.isNullOrBlank() && name.isNullOrBlank() && arguments == null) return null
        return OpenAiToolCallDelta(index, id, call.text("type"), name, arguments, call.literal())
    }

    private fun argumentsText(value: JsonValue?): String? = when (value) {
        null, JsonValue.Null -> null
        is JsonValue.Text -> value.value.takeIf(String::isNotEmpty)
        else -> value.literal()
    }

    private fun addContentParts(parts: List<JsonValue>, events: MutableList<OpenAiDecodeEvent>) {
        parts.forEach { part ->
            when (part) {
                is JsonValue.Text -> if (part.value.isNotEmpty()) events += OpenAiDecodeEvent.TextDelta(part.value)
                is JsonValue.Object -> addContentPart(part, events)
                else -> throw failure()
            }
        }
    }

    private fun addContentPart(part: JsonValue.Object, events: MutableList<OpenAiDecodeEvent>) {
        val type = part.text("type")?.lowercase(Locale.ROOT) ?: "text"
        if (type in REASONING_PART_TYPES) {
            addThinkingPart(part["thinking"] ?: part["text"], events)
        } else {
            addContent(part["text"], events)
        }
    }

    private fun addThinkingPart(value: JsonValue?, events: MutableList<OpenAiDecodeEvent>) {
        if (value is JsonValue.Array) {
            value.values.forEach { chunk -> appendReasoning(chunk.partText(), events) }
        } else {
            appendReasoning((value as? JsonValue.Text)?.value, events)
        }
    }

    private fun JsonValue.partText(): String? = when (this) {
        is JsonValue.Text -> value
        is JsonValue.Object -> text("text")
        else -> null
    }

    private fun addReasoningDetails(value: JsonValue?, events: MutableList<OpenAiDecodeEvent>) {
        val array = value as? JsonValue.Array ?: return
        array.values.forEach { raw ->
            val detail = raw as? JsonValue.Object ?: return@forEach
            appendReasoning(detail.text("text") ?: detail.text("summary"), events)
        }
    }

    private fun appendReasoning(value: String?, events: MutableList<OpenAiDecodeEvent>) {
        if (!value.isNullOrEmpty()) events += OpenAiDecodeEvent.ReasoningDelta(value)
    }

    private fun JsonValue.Object.toolIndex(): Int? =
        (this["index"] as? JsonValue.Number)?.asLongOrNull()?.takeIf { it in 0..Int.MAX_VALUE.toLong() }?.toInt()

    private fun usageEvents(frame: JsonValue.Object): List<OpenAiDecodeEvent> {
        val usage = (frame["usage"] as? JsonValue.Object)?.let(::usage) ?: return emptyList()
        return listOf(OpenAiDecodeEvent.UsageReported(usage))
    }

    private fun usage(raw: JsonValue.Object): ProviderUsage? {
        val input = raw.integer("prompt_tokens") ?: raw.integer("input_tokens")
        val output = raw.integer("completion_tokens") ?: raw.integer("output_tokens")
        val promptDetails = raw["prompt_tokens_details"] as? JsonValue.Object
        val inputDetails = raw["input_tokens_details"] as? JsonValue.Object
        val outputDetails = raw["completion_tokens_details"] as? JsonValue.Object
        val outputAliasDetails = raw["output_tokens_details"] as? JsonValue.Object
        val cached = maxOf(
            raw.integer("cache_read_input_tokens"),
            raw.integer("prompt_cache_hit_tokens"),
            raw.integer("cached_tokens"),
            promptDetails?.integer("cached_tokens"),
            inputDetails?.integer("cached_tokens"),
        )
        val cacheWrite = maxOf(
            raw.integer("cache_creation_input_tokens"),
            raw.integer("cache_write_tokens"),
            promptDetails?.integer("cache_write_tokens"),
            inputDetails?.integer("cache_write_tokens"),
        )
        val reasoning = raw.integer("reasoning_tokens")
            ?: outputDetails?.integer("reasoning_tokens")
            ?: outputAliasDetails?.integer("reasoning_tokens")
        return ProviderUsage(
            inputTokens = input,
            cachedInputTokens = cached,
            cacheWriteInputTokens = cacheWrite,
            outputTokens = output,
            reasoningTokens = reasoning,
            totalTokens = raw.integer("total_tokens"),
        ).takeIf { it.known }
    }

    private fun JsonValue.Object.integer(key: String): Long? {
        val raw = this[key] ?: return null
        if (raw == JsonValue.Null) return null
        val number = raw as? JsonValue.Number ?: throw failure()
        if (!number.isIntegerLiteral()) throw failure()
        return number.asLongOrNull()?.takeIf { it >= 0 } ?: throw failure()
    }

    private fun JsonValue.Object.error(): OpenAiDecodeEvent.ProviderError? {
        val raw = this["error"] ?: return null
        if (raw == JsonValue.Null) return null
        val objectValue = raw as? JsonValue.Object
        return OpenAiDecodeEvent.ProviderError(
            code = objectValue?.text("code") ?: (raw as? JsonValue.Text)?.value,
            message = objectValue?.text("message"),
        )
    }

    private fun JsonValue.Object.text(key: String): String? = (this[key] as? JsonValue.Text)?.value

    private fun failure() = ProviderException(ProviderFailure(ProviderFailureCode.PROVIDER_ERROR))

    companion object {
        const val DONE = "[DONE]"
        private val REASONING_PART_TYPES = setOf("thinking", "think", "reasoning")
    }
}

private fun maxOf(vararg values: Long?): Long? = values.filterNotNull().maxOrNull()

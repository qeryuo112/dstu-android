package com.deepstudent.services.providers.data

import com.deepstudent.core.common.json.JsonValue
import java.util.UUID

/**
 * Port of the frozen Gemini stream-line parser
 * (host/plugins/llm-providers/src/adapters/gemini-openai-response.ts,
 * `parse_gemini_stream_line`). One decoder instance owns the pending tool-call
 * state of exactly one stream, never a shared provider instance.
 */
internal class GoogleGeminiStreamDecoder {
    private val pendingToolCalls = LinkedHashMap<Int, PendingToolCall>()

    /** Terminal frames stop the SSE read; blocked/done keep already-delivered fragments. */
    var terminal: Boolean = false
        private set

    fun accept(data: String): List<GoogleGeminiStreamEvent> {
        if (terminal) return emptyList()
        val payload = data.trim()
        if (payload.isEmpty()) return emptyList()
        if (payload == DONE_MARKER) {
            pendingToolCalls.clear()
            terminal = true
            return listOf(GoogleGeminiStreamEvent.Done)
        }
        val json = runCatching { JsonValue.parse(payload) }.getOrNull() ?: return emptyList()
        return parsePayload(json)
    }

    private fun parsePayload(json: JsonValue): List<GoogleGeminiStreamEvent> {
        val events = mutableListOf<GoogleGeminiStreamEvent>()
        val candidates = jsonArray(json, "candidates")?.values.orEmpty()
        candidates.firstOrNull()?.let { parseCandidate(it, events) }
        pushCandidateThoughts(json, events)
        jsonObj(json, "usageMetadata")?.let { events += usageEvent(it) }
        appendTerminals(json, candidates, events)
        if (terminal) pendingToolCalls.clear()
        return events
    }

    private fun parseCandidate(candidate: JsonValue, events: MutableList<GoogleGeminiStreamEvent>) {
        jsonArray(jsonObj(candidate, "content"), "parts")?.values.orEmpty().forEach { parseContentPart(it, events) }
        pushCandidateThoughts(candidate, events)
        jsonText(candidate, "thoughtSignature")?.takeIf(String::isNotEmpty)?.let {
            events += GoogleGeminiStreamEvent.Signature(it)
        }
        jsonArray(jsonObj(candidate, "delta"), "parts")?.values.orEmpty().forEach { parseDeltaPart(it, events) }
    }

    private fun appendTerminals(json: JsonValue, candidates: List<JsonValue>, events: MutableList<GoogleGeminiStreamEvent>) {
        val feedback = jsonObj(json, "promptFeedback")
        if (feedback?.containsKey("blockReason") == true) {
            terminal = true
            events += GoogleGeminiStreamEvent.Blocked(BLOCK_PROMPT, jsonText(feedback, "blockReason").orEmpty())
        }
        candidates.mapNotNull { jsonText(it, "finishReason") }.filterNot { it == "STOP" }.forEach { reason ->
            terminal = true
            val type = if (reason == "SAFETY") BLOCK_CONTENT else BLOCK_PROVIDER
            events += GoogleGeminiStreamEvent.Blocked(type, reason)
        }
        if (jsonText(candidates.firstOrNull(), "finishReason") == "STOP") {
            terminal = true
            events += GoogleGeminiStreamEvent.Done
        }
    }

    /** Frozen content-parts loop: thought markers are lenient and every part keeps signature/tool handling. */
    private fun parseContentPart(part: JsonValue, events: MutableList<GoogleGeminiStreamEvent>) {
        if (GoogleGeminiStreamDecoder.isThoughtPart(part, allowNonBool = true)) {
            GoogleGeminiStreamDecoder.pushThoughtParts(part, events)
        } else {
            jsonText(part, "text")?.takeIf(String::isNotEmpty)?.let { events += GoogleGeminiStreamEvent.Text(it) }
        }
        jsonArray(part, "thoughts")?.values?.forEach { item ->
            GoogleGeminiStreamDecoder.extractThoughtTexts(item).filter(String::isNotEmpty)
                .forEach { events += GoogleGeminiStreamEvent.Reasoning(it) }
        }
        jsonText(part, "thoughtSignature")?.takeIf(String::isNotEmpty)?.let {
            events += GoogleGeminiStreamEvent.Signature(it)
        }
        pushToolCall(part, events)
    }

    /** Frozen delta-parts loop: strict thought flag, thought parts skip signature/tool handling. */
    private fun parseDeltaPart(part: JsonValue, events: MutableList<GoogleGeminiStreamEvent>) {
        if (GoogleGeminiStreamDecoder.isThoughtPart(part, allowNonBool = false)) {
            GoogleGeminiStreamDecoder.pushThoughtParts(part, events)
            return
        }
        jsonText(part, "text")?.takeIf(String::isNotEmpty)?.let { events += GoogleGeminiStreamEvent.Text(it) }
        jsonArray(part, "thoughts")?.values?.forEach { item ->
            val extracted = GoogleGeminiStreamDecoder.extractThoughtTexts(item)
            if (extracted.isEmpty()) {
                val fallback = jsonText(item, "text") ?: jsonText(item, "content")
                if (fallback != null && fallback.isNotEmpty()) events += GoogleGeminiStreamEvent.Reasoning(fallback)
            } else {
                extracted.filter(String::isNotEmpty).forEach { events += GoogleGeminiStreamEvent.Reasoning(it) }
            }
        }
        pushToolCall(part, events)
    }

    private fun pushToolCall(part: JsonValue, events: MutableList<GoogleGeminiStreamEvent>) {
        val call = jsonObj(part, "functionCall") ?: return
        val name = jsonText(call, "name") ?: return
        val args = call["args"] ?: JsonValue.Object.EMPTY
        val nativeId = jsonText(call, "id")?.trim()?.takeIf(String::isNotEmpty)
        val existing = nativeId?.let { id -> pendingToolCalls.entries.firstOrNull { it.value.id == id } }
        val index = existing?.key ?: pendingToolCalls.size
        if (existing == null) {
            pendingToolCalls[index] = PendingToolCall(id = nativeId ?: "call-${UUID.randomUUID()}", name = name)
        }
        events += GoogleGeminiStreamEvent.ToolCall(index, checkNotNull(pendingToolCalls[index]).id, name, args.literal())
    }

    private fun usageEvent(usage: JsonValue.Object): GoogleGeminiStreamEvent.Usage {
        val prompt = usage["promptTokenCount"].longOrZero()
        val completion = usage["candidatesTokenCount"].longOrZero()
        val total = usage["totalTokenCount"].longOrNull() ?: (prompt + completion)
        val cachedKey = if (usage.containsKey("cachedContentTokenCount")) "cachedContentTokenCount" else "cacheReadInputTokens"
        return GoogleGeminiStreamEvent.Usage(
            prompt = prompt,
            completion = completion,
            total = total,
            thoughts = usage["thoughtsTokenCount"].longOrNull(),
            cached = usage[cachedKey].longOrNull(),
            raw = usage,
        )
    }

    private data class PendingToolCall(val id: String, val name: String)

    companion object {
        private const val DONE_MARKER = "[DONE]"
        internal const val BLOCK_PROMPT = "prompt_blocked"
        internal const val BLOCK_CONTENT = "content_blocked"
        internal const val BLOCK_PROVIDER = "provider_error"

        /** Frozen `is_thought_part`; the streaming branch accepts lenient thought markers. */
        internal fun isThoughtPart(part: JsonValue, allowNonBool: Boolean): Boolean {
            val thought = asJsonObject(part)?.get("thought")
            if (thought == JsonValue.Bool(true)) return true
            if (allowNonBool && lenientThought(thought)) return true
            val metadata = jsonObj(part, "metadata")
            return thoughtTag(metadata, "type") || metadata?.get("isThought") == JsonValue.Bool(true) ||
                thoughtTag(part, "kind") || thoughtTag(part, "type")
        }

        private fun lenientThought(thought: JsonValue?): Boolean = when (thought) {
            is JsonValue.Text -> thought.value.trim().isNotEmpty()
            is JsonValue.Object -> (thought["value"] as? JsonValue.Bool)?.value ?: true
            else -> false
        }

        private fun thoughtTag(value: JsonValue?, key: String): Boolean = jsonText(value, key)?.geminiAsciiLower() == "thought"

        /** Frozen `extract_thought_texts`: strings, arrays, and nested text/content/parts/data. */
        internal fun extractThoughtTexts(value: JsonValue): List<String> = when (value) {
            is JsonValue.Text -> listOf(value.value)

            is JsonValue.Array -> value.values.flatMap(::extractThoughtTexts)

            is JsonValue.Object -> buildList {
                jsonText(value, "text")?.let { add(it) }
                value["content"]?.let { addAll(extractThoughtTexts(it)) }
                jsonArray(value, "parts")?.values?.forEach { addAll(extractThoughtTexts(it)) }
                value["data"]?.let { addAll(extractThoughtTexts(it)) }
            }

            else -> emptyList()
        }

        /** Frozen `push_thought_parts`: extracted snippets, or the bare text fallback. */
        internal fun pushThoughtParts(part: JsonValue, events: MutableList<GoogleGeminiStreamEvent>) {
            val extracted = extractThoughtTexts(part)
            if (extracted.isEmpty()) {
                jsonText(part, "text")?.takeIf(String::isNotEmpty)?.let { events += GoogleGeminiStreamEvent.Reasoning(it) }
                return
            }
            extracted.filter(String::isNotEmpty).forEach { events += GoogleGeminiStreamEvent.Reasoning(it) }
        }

        /** Frozen `push_candidate_thoughts`: first text/content alias or a bare string item. */
        private fun pushCandidateThoughts(holder: JsonValue?, events: MutableList<GoogleGeminiStreamEvent>) {
            val items = jsonArray(holder, "thoughts") ?: return
            for (item in items.values) {
                val text = jsonText(item, "text") ?: jsonText(item, "content") ?: (item as? JsonValue.Text)?.value
                if (text != null && text.isNotEmpty()) events += GoogleGeminiStreamEvent.Reasoning(text)
            }
        }
    }
}

/** Decoded Gemini stream events before mapping onto the shared provider contract. */
internal sealed interface GoogleGeminiStreamEvent {
    data class Text(val value: String) : GoogleGeminiStreamEvent

    data class Reasoning(val value: String) : GoogleGeminiStreamEvent

    /** Gemini always delivers a complete argument snapshot, never fragments. */
    data class ToolCall(val index: Int, val id: String, val name: String, val argumentsSnapshot: String) : GoogleGeminiStreamEvent

    data class Signature(val value: String) : GoogleGeminiStreamEvent

    data class Usage(
        val prompt: Long,
        val completion: Long,
        val total: Long,
        val thoughts: Long?,
        val cached: Long?,
        val raw: JsonValue.Object,
    ) : GoogleGeminiStreamEvent

    data class Blocked(val type: String, val reason: String) : GoogleGeminiStreamEvent

    data object Done : GoogleGeminiStreamEvent
}

private fun JsonValue?.longOrNull(): Long? = (this as? JsonValue.Number)?.asLongOrNull()

private fun JsonValue?.longOrZero(): Long = longOrNull() ?: 0L

private fun jsonObj(value: JsonValue?, key: String): JsonValue.Object? {
    val holder = value as? JsonValue.Object ?: return null
    return holder[key] as? JsonValue.Object
}

private fun jsonArray(value: JsonValue?, key: String): JsonValue.Array? {
    val holder = value as? JsonValue.Object ?: return null
    return holder[key] as? JsonValue.Array
}

private fun jsonText(value: JsonValue?, key: String): String? {
    val holder = value as? JsonValue.Object ?: return null
    return (holder[key] as? JsonValue.Text)?.value
}

private fun asJsonObject(value: JsonValue?): JsonValue.Object? = value as? JsonValue.Object

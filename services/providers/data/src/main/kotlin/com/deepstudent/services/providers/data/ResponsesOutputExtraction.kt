package com.deepstudent.services.providers.data

import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.services.providers.api.ProviderUsage
import java.net.URI
import java.util.Locale

/**
 * Final-response extraction for the Responses protocol, ported from the frozen desktop
 * helpers in `host/plugins/llm-providers/src/providers/openai-responses.ts`
 * (`providers/mod.rs:1131-1955`) and `llm/src/usage.ts` at
 * e6b2657fd59014a73f5bbb1d14c2db732cf23e87.
 *
 * These functions only report wire facts. The frozen desktop's token estimation and
 * content-length heuristics belong to the desktop host; on Android that estimation is
 * owned by the chat turn layer, so absent usage fields stay null instead of being
 * synthesized here.
 */
internal const val RESPONSES_REFUSAL_FALLBACK = "DeepSeek blocked this response."

/**
 * `extract_usage_tokens` restricted to wire facts: integer token counts only, clamped
 * to the unsigned 32-bit range the frozen desktop accepts. Explicit zeros are real
 * measurements on the wire; a larger total can still fill an absent or zero input
 * count, and anything else stays unknown.
 */
internal fun responsesUsage(raw: JsonValue?): ProviderUsage? {
    val usage = raw as? JsonValue.Object ?: return null
    return usageOrNull(usage).takeIf { it.known }
}

private fun usageOrNull(usage: JsonValue.Object): ProviderUsage {
    val outputTokens = completionTokens(usage)
    val wireTotal = usage.u32At("total_tokens") ?: usage.u32At("totalTokenCount")
    val inputTokens = deriveInputTokens(promptTokens(usage), outputTokens, wireTotal)
    return ProviderUsage(
        inputTokens = inputTokens,
        cachedInputTokens = cachedTokens(usage),
        cacheWriteInputTokens = cacheWriteTokens(usage),
        outputTokens = outputTokens,
        reasoningTokens = reasoningTokens(usage),
        // An inconsistent wire total would be rejected by ProviderUsage; the valid
        // input/output counts stay, only the contradictory total is dropped.
        totalTokens = wireTotal?.takeIf { consistentTotal(inputTokens, outputTokens, it) },
    )
}

private fun promptTokens(usage: JsonValue.Object): Long? =
    usage.u32At("prompt_tokens") ?: usage.u32At("input_tokens") ?: usage.u32At("promptTokenCount")

private fun completionTokens(usage: JsonValue.Object): Long? =
    usage.u32At("completion_tokens") ?: usage.u32At("output_tokens") ?: usage.u32At("candidatesTokenCount")

private fun reasoningTokens(usage: JsonValue.Object): Long? = usage.u32At("reasoning_tokens")
    ?: usage.u32At("thoughtsTokenCount")
    ?: usage.objectAt("completion_tokens_details")?.u32At("reasoning_tokens")
    ?: usage.objectAt("output_tokens_details")?.u32At("reasoning_tokens")

private fun cachedTokens(usage: JsonValue.Object): Long? = maxKnownTokenCount(
    usage.u32At("cache_read_input_tokens"),
    usage.objectAt("prompt_tokens_details")?.u32At("cached_tokens"),
    usage.objectAt("input_tokens_details")?.u32At("cached_tokens"),
    usage.u32At("prompt_cache_hit_tokens"),
    usage.u32At("cached_tokens"),
)

private fun cacheWriteTokens(usage: JsonValue.Object): Long? = maxKnownTokenCount(
    usage.u32At("cache_creation_input_tokens"),
    usage.objectAt("input_tokens_details")?.u32At("cache_write_tokens"),
    usage.u32At("cache_write_tokens"),
)

private fun consistentTotal(inputTokens: Long?, outputTokens: Long?, total: Long): Boolean =
    inputTokens == null || outputTokens == null || inputTokens + outputTokens == total

/**
 * The frozen desktop replaces an absent or zero input count with a total-derived
 * value (`extract_usage_tokens`, `usage.ts:70-76`); when no larger total exists the
 * desktop falls back to chat-turn estimation, which the decoder does not own, so the
 * unknown count stays null here.
 */
private fun deriveInputTokens(inputTokens: Long?, outputTokens: Long?, totalTokens: Long?): Long? {
    if (inputTokens != null && inputTokens != 0L) return inputTokens
    if (totalTokens == null || outputTokens == null || totalTokens <= outputTokens) return null
    return totalTokens - outputTokens
}

/** `max_defined` from the frozen usage extraction: the largest present count wins. */
private fun maxKnownTokenCount(vararg values: Long?): Long? = values.filterNotNull().maxOrNull()

/**
 * `extract_output_text` (providers/mod.rs:1877): output parts in wire order joined
 * without separator, falling back to `content` and the convenience `output_text` field.
 */
internal fun extractOutputText(response: JsonValue.Object): String? {
    val segments = mutableListOf<String>()
    collectFromOutput(response, segments)
    if (segments.isEmpty()) collectOutputText(response["content"], segments)
    if (segments.isEmpty()) response.textAt("output_text")?.takeIf { it.isNotEmpty() }?.let { segments += it }
    return segments.takeIf { it.isNotEmpty() }?.joinToString("")
}

private fun collectFromOutput(response: JsonValue.Object, segments: MutableList<String>) {
    when (val output = response["output"]) {
        is JsonValue.Text -> if (output.value.isNotEmpty()) segments += output.value
        is JsonValue.Array -> for (raw in output.values) collectOutputItem(raw, segments)
        else -> Unit
    }
}

private fun collectOutputItem(raw: JsonValue, segments: MutableList<String>) {
    val item = raw as? JsonValue.Object ?: return
    val content = item["content"]
    if (content != null) {
        collectOutputText(content, segments)
        return
    }
    if (item.textAt("type") == "output_text" || item.textAt("type") == "text") {
        val text = item.textAt("text")
        if (!text.isNullOrEmpty()) segments += text
    }
}

/** `collect_output_text`: plain strings and `output_text`/`text` parts count as body text. */
private fun collectOutputText(content: JsonValue?, segments: MutableList<String>) {
    when (content) {
        is JsonValue.Text -> if (content.value.isNotEmpty()) segments += content.value
        is JsonValue.Array -> for (raw in content.values) collectOutputPart(raw, segments)
        else -> Unit
    }
}

private fun collectOutputPart(raw: JsonValue, segments: MutableList<String>) {
    val part = raw as? JsonValue.Object ?: return
    val type = part.textAt("type")
    if (type == "output_text" || type == "text" || type == null) {
        val text = part.textAt("text")
        if (!text.isNullOrEmpty()) segments += text
    }
}

/**
 * `extract_reasoning_text` (providers/mod.rs:1811): reasoning summaries and reasoning
 * content entries, joined with a blank line between segments.
 */
internal fun extractReasoningText(response: JsonValue.Object): String? {
    val segments = mutableListOf<String>()
    for (raw in response.arrayAt("output")?.values ?: emptyList()) {
        collectReasoningSegments(raw, segments)
    }
    return segments.takeIf { it.isNotEmpty() }?.joinToString("\n\n")
}

private fun collectReasoningSegments(raw: JsonValue, segments: MutableList<String>) {
    val item = raw as? JsonValue.Object ?: return
    val itemType = item.textAt("type") ?: ""
    if (itemType == RESPONSES_TYPE_REASONING) collectEntryText(item.arrayAt("summary"), segments)
    val content = item.arrayAt("content")
    if (content != null) {
        collectReasoningTypedText(content, segments)
    } else if (itemType.contains("reasoning")) {
        appendSegment(item.textAt("text"), segments)
    }
}

private fun collectEntryText(entries: JsonValue.Array?, segments: MutableList<String>) {
    for (raw in entries?.values ?: emptyList()) appendSegment((raw as? JsonValue.Object)?.textAt("text"), segments)
}

private fun collectReasoningTypedText(content: JsonValue.Array, segments: MutableList<String>) {
    for (raw in content.values) {
        val part = raw as? JsonValue.Object ?: continue
        if ((part.textAt("type") ?: "").contains("reasoning")) appendSegment(part.textAt("text"), segments)
    }
}

private fun appendSegment(text: String?, segments: MutableList<String>) {
    if (!text.isNullOrEmpty()) segments += text
}

/** `deepseekRefusal`: the refusal text of a moderated response, or the fixed fallback phrase. */
internal fun deepseekRefusal(response: JsonValue.Object): String? {
    for (raw in response.arrayAt("output")?.values ?: emptyList()) {
        val item = raw as? JsonValue.Object ?: continue
        val refusal = itemRefusal(item)
        if (refusal != null) return refusal
    }
    return null
}

private fun itemRefusal(item: JsonValue.Object): String? {
    for (raw in item.arrayAt("content")?.values ?: emptyList()) {
        val part = raw as? JsonValue.Object ?: continue
        if (part.textAt("type") == "refusal") {
            return part.textAt("refusal")?.takeIf { it.isNotEmpty() } ?: RESPONSES_REFUSAL_FALLBACK
        }
    }
    return null
}

/**
 * `deepseekCitedText`: renders DeepSeek's Unicode code-point citation offsets without
 * changing the quoted text. Citations are numbered per final response and inserted
 * after the character each annotation ends on; only plain http(s) URLs with a real
 * host and no user info pass the allowlist, and marker text escapes `<`/`>`. Falls
 * back to [extractOutputText] when no cited part exists.
 */
internal fun deepseekCitedText(response: JsonValue.Object): String? {
    val segments = mutableListOf<String>()
    val citationNumbers = mutableMapOf<String, Int>()
    for (raw in response.arrayAt("output")?.values ?: emptyList()) {
        val item = raw as? JsonValue.Object ?: continue
        citedSegment(item, segments, citationNumbers)
    }
    return segments.takeIf { it.isNotEmpty() }?.joinToString("") ?: extractOutputText(response)
}

private fun citedSegment(item: JsonValue.Object, segments: MutableList<String>, citationNumbers: MutableMap<String, Int>) {
    if (item.textAt("type") != RESPONSES_TYPE_MESSAGE) return
    for (raw in item.arrayAt("content")?.values ?: emptyList()) {
        val part = raw as? JsonValue.Object ?: continue
        citedPartText(part, segments, citationNumbers)
    }
}

private fun citedPartText(part: JsonValue.Object, segments: MutableList<String>, citationNumbers: MutableMap<String, Int>) {
    val text = part.textAt("text")
    if (part.textAt("type") != "output_text" || text.isNullOrEmpty()) return
    val chars = codePointChars(text)
    val markers = citationMarkers(part, chars.size, citationNumbers)
    segments += chars.mapIndexed { index, char -> char + markerSuffix(markers, index + 1, citationNumbers) }.joinToString("")
}

private fun citationMarkers(part: JsonValue.Object, charCount: Int, citationNumbers: MutableMap<String, Int>): Map<Int, Set<String>> {
    val markers = mutableMapOf<Int, MutableSet<String>>()
    for (raw in part.arrayAt("annotations")?.values ?: emptyList()) {
        collectMarker(raw, charCount, citationNumbers, markers)
    }
    return markers
}

private fun collectMarker(
    raw: JsonValue,
    charCount: Int,
    citationNumbers: MutableMap<String, Int>,
    markers: MutableMap<Int, MutableSet<String>>,
) {
    val annotation = raw as? JsonValue.Object ?: return
    val end = citationEndIndex(annotation, charCount) ?: return
    val href = citationHref(annotation) ?: return
    if (href !in citationNumbers) citationNumbers[href] = citationNumbers.size + 1
    markers.getOrPut(end) { LinkedHashSet() }.add(href)
}

/** `url_citation` bounds check: valid offsets stay inside the code-point char list. */
private fun citationEndIndex(annotation: JsonValue.Object, charCount: Int): Int? {
    if (annotation.textAt("type") != "url_citation") return null
    val start = annotation.i64At("start_index") ?: return null
    val end = annotation.i64At("end_index") ?: return null
    if (start < 0 || start >= end || end > charCount) return null
    return end.toInt()
}

private fun markerSuffix(markers: Map<Int, Set<String>>, endIndex: Int, citationNumbers: Map<String, Int>): String =
    (markers[endIndex] ?: emptySet()).joinToString("") { citationMarker(it, citationNumbers) }

private fun citationMarker(href: String, citationNumbers: Map<String, Int>): String {
    val escaped = href.replace("<", "%3C").replace(">", "%3E")
    return " [${citationNumbers.getValue(href)}](<$escaped>)"
}

/**
 * `URL.parse` plus the frozen allowlist: http(s) only, a real authority host and no
 * embedded user info. Opaque `https:...` forms without a host are rejected as the
 * Android citation boundary instead of being re-hosted like the desktop URL parser.
 */
private fun citationHref(annotation: JsonValue.Object): String? {
    val url = annotation.textAt("url") ?: return null
    val parsed = try {
        URI(url)
    } catch (_: Exception) {
        return null
    }
    val scheme = parsed.scheme?.lowercase(Locale.ROOT)
    if (scheme != "https" && scheme != "http") return null
    if (parsed.host.isNullOrEmpty()) return null
    if (!parsed.userInfo.isNullOrEmpty()) return null
    return parsed.toString()
}

/** `Array.from(text)`: one entry per Unicode code point, so offsets match DeepSeek's. */
private fun codePointChars(text: String): List<String> =
    text.codePoints().toArray().map { codePoint -> String(Character.toChars(codePoint)) }

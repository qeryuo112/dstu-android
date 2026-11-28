package com.deepstudent.services.providers.data

import com.deepstudent.core.common.json.JsonValue
import java.net.URI

/**
 * Port of the frozen Gemini wire conversion pipeline
 * (host/plugins/llm-providers/src/adapters/gemini-openai-converter.ts,
 * `build_gemini_request_with_version`). Pure: no credential and no network I/O
 * live here; the key query is appended by the port. Desktop `logger.warn`
 * behavior becomes desensitized [GoogleGeminiConverter.Stats] counters.
 */
internal object GoogleGeminiConverter {
    class Stats {
        var mergedTurns: Int = 0
        var unsupportedParts: Int = 0
        var downgradedToolParts: Int = 0
        var insertedUserTurn: Boolean = false

        fun fields(): List<Pair<String, String>> = listOf(
            "mergedTurns" to mergedTurns.toString(),
            "unsupportedParts" to unsupportedParts.toString(),
            "downgradedToolParts" to downgradedToolParts.toString(),
            "insertedUserTurn" to insertedUserTurn.toString(),
        )
    }

    /** Rebuilt same-origin request without the credential query. */
    class Wire(val url: String, val body: JsonValue.Object, val version: String, val model: String)

    fun build(model: String, openaiBody: JsonValue, route: GoogleGeminiRoute, stats: Stats = Stats()): Wire {
        val (prefixVersion, actualModel) = modelVersionPrefix(model)
        val normalized = normalize(openaiBody)
        val request = OpenAiShape.parse(normalized.body)
        val draft = convertOpenAiToGemini(request, stats)
        prefixTopK(draft, normalized.topK)
        resolveThinking(draft, normalized, actualModel)
        val version = resolveVersion(prefixVersion ?: normalized.version, route.version, draft, actualModel)
        if (version == "v1") mergeSystemInstructionForV1(draft)
        val url = "${route.origin}${route.rootPath}/$version/models/${encodeModelPath(actualModel)}:streamGenerateContent?alt=sse"
        return Wire(url, draft.body(), version, actualModel)
    }

    /** Frozen pre-parse normalization; deleted keys never reach the strict parse. */
    private fun normalize(openaiBody: JsonValue): NormalizedBody {
        val body = openaiBody as? JsonValue.Object
            ?: throw buildFailure("serialization_error", "invalid type: expected struct OpenAIRequest")
        val fields = LinkedHashMap(body.asMap())
        normalizeTokenField(fields, "max_total_tokens")
        normalizeTokenField(fields, "max_completion_tokens")
        val effort = (fields.remove("reasoning_effort") as? JsonValue.Text)?.value
        val thinkingConfig = when {
            fields.containsKey("google_thinking_config") -> fields.remove("google_thinking_config")
            fields.containsKey("thinkingConfig") -> fields.remove("thinkingConfig")
            else -> null
        }
        val version = (fields.remove("gemini_api_version") as? JsonValue.Text)?.value?.takeIf { it == "v1" || it == "v1beta" }
        val topK = (fields["top_k"] as? JsonValue.Number)?.asLongOrNull()?.coerceIn(1L, 1_000_000L)
        return NormalizedBody(JsonValue.Object.from(fields), effort, thinkingConfig, topK, version)
    }

    private fun normalizeTokenField(fields: LinkedHashMap<String, JsonValue>, key: String) {
        val value = fields.remove(key) ?: return
        if (!fields.containsKey("max_tokens")) fields["max_tokens"] = value
    }

    /** Frozen model-prefix rule: `v1:`/`v1beta:` wins over every other version source. */
    internal fun modelVersionPrefix(model: String): Pair<String?, String> {
        val colon = model.indexOf(':')
        if (colon < 0) return null to model
        val prefix = model.substring(0, colon)
        return if (prefix == "v1" || prefix == "v1beta") prefix to model.substring(colon + 1) else null to model
    }

    private fun prefixTopK(draft: Draft, topK: Long?) {
        if (topK == null) return
        writeGenerationConfig(draft, "topK", JsonValue.Number.integer(topK))
    }

    /** Frozen thinking resolution: explicit google/thinking configs, then effort mapping. */
    private fun resolveThinking(draft: Draft, normalized: NormalizedBody, model: String) {
        val extra = normalized.thinkingConfig as? JsonValue.Object
        val lower = model.geminiAsciiLower()
        val effort = normalized.reasoningEffort?.geminiAsciiLower()
        val thinking = JsonValue.Object.Builder()
        val budget = extra.pick("thinking_budget", "thinkingBudget").numberOrNull()?.coerceIn(-1L, Int.MAX_VALUE.toLong())
        val level = extra.pick("thinking_level", "thinkingLevel").textOrNull()
        if (lower.contains("gemini-3")) {
            thinking.putIfNotNull(
                "thinkingLevel",
                (
                    effort?.let {
                        effortLevel(it, lower.contains("flash"))
                    } ?: level
                    )?.let(JsonValue::text),
            )
        } else {
            thinking.putIfNotNull("thinkingBudget", (budget ?: effort?.let(::effortBudget))?.let(JsonValue::integer))
        }
        val include = extra.pick("include_thoughts", "includeThoughts").boolOrNull()
        thinking.putIfNotNull("includeThoughts", include?.let(JsonValue::bool))
        val config = thinking.build()
        if (!config.isEmpty() || level != null || budget != null) writeGenerationConfig(draft, "thinkingConfig", config)
    }

    private fun effortLevel(effort: String, flash: Boolean): String = when (effort) {
        "minimal", "none", "unset" -> if (flash) "minimal" else "low"
        "medium" -> if (flash) "medium" else "high"
        "high" -> "high"
        else -> "low"
    }

    private fun effortBudget(effort: String): Long? = when (effort) {
        "minimal" -> 256L
        "low" -> 1_024L
        "medium" -> 8_192L
        "high" -> 24_576L
        "none", "unset" -> 0L
        else -> null
    }

    /**
     * Frozen version rule: the model prefix wins; a thinking config, a system
     * instruction or a Gemini 3 model force v1beta (v1 upgrades, v1beta stays).
     */
    private fun resolveVersion(prefixVersion: String?, routeVersion: String, draft: Draft, model: String): String {
        var resolved = prefixVersion ?: routeVersion
        val thinkingPresent = draft.generationConfig?.objectFields()?.get("thinkingConfig") is JsonValue.Object
        val requiresV1beta = thinkingPresent || draft.systemInstruction != null ||
            model.geminiAsciiLower().contains("gemini-3")
        if (requiresV1beta && resolved == "v1") resolved = "v1beta"
        return resolved
    }

    /** v1 has no systemInstruction field: merge it into the first content part. */
    private fun mergeSystemInstructionForV1(draft: Draft) {
        val instruction = draft.systemInstruction ?: return
        val texts = instruction.parts.mapNotNull { (it.objectFields()["text"] as? JsonValue.Text)?.value }
            .filter { it.trim().isNotEmpty() }
        if (texts.isNotEmpty()) {
            val part = JsonValue.obj("text" to JsonValue.text(texts.joinToString("\n\n")))
            val first = draft.contents.firstOrNull()
            if (first != null) first.parts.add(0, part) else draft.contents += Content("user", mutableListOf(part))
        }
        draft.systemInstruction = null
    }

    /** Frozen model path encoding (route-level `URI` path encoding with flat slashes). */
    private fun encodeModelPath(model: String): String {
        val raw = runCatching { URI(null, null, "/$model", null).rawPath.orEmpty() }
            .getOrElse { throw buildFailure("invalid_format", "invalid model path") }
        return raw.removePrefix("/").replace("/", "%2F")
    }

    internal class Draft {
        val contents = mutableListOf<Content>()
        var systemInstruction: Content? = null
        var generationConfig: JsonValue.Object? = null
        var tools: JsonValue? = null
        var toolConfig: JsonValue.Object? = null

        fun body(): JsonValue.Object {
            val fields = LinkedHashMap<String, JsonValue>()
            fields["contents"] = JsonValue.array(contents.map { it.json() })
            systemInstruction?.let { fields["systemInstruction"] = it.json() }
            generationConfig?.let { fields["generationConfig"] = it }
            tools?.let { fields["tools"] = it }
            toolConfig?.let { fields["toolConfig"] = it }
            return JsonValue.Object.from(fields)
        }
    }

    internal class Content(val role: String, val parts: MutableList<JsonValue>) {
        fun json(): JsonValue.Object = JsonValue.obj("role" to JsonValue.text(role), "parts" to JsonValue.array(parts.toList()))
    }

    /** The mutable view is written back: [JsonValue.Object] copies are immutable snapshots. */
    internal fun writeGenerationConfig(draft: Draft, key: String, value: JsonValue) {
        val existing = draft.generationConfig
        val fields = if (existing == null) LinkedHashMap() else LinkedHashMap(existing.asMap())
        fields[key] = value
        draft.generationConfig = JsonValue.Object.from(fields)
    }

    internal fun buildFailure(kind: String, detail: String): GoogleGeminiBuildException = GoogleGeminiBuildException(kind, detail)

    private class NormalizedBody(
        val body: JsonValue.Object,
        val reasoningEffort: String?,
        val thinkingConfig: JsonValue?,
        val topK: Long?,
        val version: String?,
    )
}

private fun JsonValue?.pick(snake: String, camel: String): JsonValue? {
    val holder = this as? JsonValue.Object ?: return null
    return if (holder.asMap().containsKey(snake)) holder[snake] else holder[camel]
}

private fun JsonValue?.numberOrNull(): Long? = (this as? JsonValue.Number)?.asLongOrNull()

private fun JsonValue?.textOrNull(): String? = (this as? JsonValue.Text)?.value

private fun JsonValue?.boolOrNull(): Boolean? = (this as? JsonValue.Bool)?.value

private fun JsonValue.objectFields(): Map<String, JsonValue> = (this as? JsonValue.Object)?.asMap() ?: emptyMap()

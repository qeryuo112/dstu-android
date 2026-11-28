package com.deepstudent.services.providers.data

import com.deepstudent.core.common.json.JsonValue
import java.util.Locale

/** One fetched vendor model entry (desktop `FetchedVendorModel`). */
data class FetchedVendorModel(val id: String, val label: String, val contextWindow: Int?, val maxOutputTokens: Int?)

/**
 * Single model-list page parsing for the three dialects, mirroring the frozen
 * `parse_vendor_models_page` and `probe_u32_field`.
 */
object VendorModelPage {
    val PROBE_CONTEXT_WINDOW_KEYS: List<String> = listOf("context_length", "context_window", "max_context_length", "max_model_len")
    val PROBE_MAX_OUTPUT_KEYS: List<String> = listOf("max_output_tokens", "max_completion_tokens", "top_provider.max_completion_tokens")

    private val OPENAI_EXCLUDED_MODEL_NEEDLES = listOf("tts", "whisper", "video", "kolors", "flux", "dall-e", "audio")
    const val GEMINI_PAGE_SIZE: Int = 100
    const val ANTHROPIC_PAGE_LIMIT: Int = 1000

    /** Reads only positive integers (JSON number or numeric string); zero and overflow are ignored. */
    fun probeU32Field(item: JsonValue?, keys: List<String>): Int? {
        for (key in keys) {
            val number = integralAt(item, key) ?: continue
            if (number > 0xFFFF_FFFF) continue
            if (number > 0) return number.toInt()
        }
        return null
    }

    private fun integralAt(item: JsonValue?, key: String): Long? = when (val value = valueAtPath(item, key)) {
        is JsonValue.Number -> integralNumber(value)
        is JsonValue.Text -> value.value.trim().takeIf { it.matches(DIGITS) }?.toLongOrNull()
        else -> null
    }

    /** JS `Number.isInteger` parity: finite, whole, within the safe integer range. */
    private fun integralNumber(value: JsonValue.Number): Long? {
        val parsed = value.literal.toDoubleOrNull()?.takeIf {
            it.isFinite() && it >= 0 && it == kotlin.math.floor(it) && it <= SAFE_INTEGER
        }
        return parsed?.toLong()
    }

    private const val SAFE_INTEGER = 9_007_199_254_740_991.0
    private val DIGITS = Regex("\\d+")

    private fun valueAtPath(item: JsonValue?, path: String): JsonValue? {
        var current: JsonValue? = item
        for (segment in path.split('.')) {
            current = (current as? JsonValue.Object)?.get(segment)
        }
        return current
    }

    fun parsePage(body: JsonValue, kind: ModelListEndpoint.Kind): ParsedPage {
        val root = body as? JsonValue.Object
        return when (kind) {
            ModelListEndpoint.Kind.GEMINI -> parseGemini(root)
            ModelListEndpoint.Kind.ANTHROPIC, ModelListEndpoint.Kind.OPENAI -> parseData(root, kind)
        }
    }

    private fun parseGemini(root: JsonValue.Object?): ParsedPage {
        val items = (root?.get("models") as? JsonValue.Array)?.values
            ?: throw ModelListException(ModelListFailureCode.MISSING_MODELS_CONTAINER)
        val models = LinkedHashMap<String, FetchedVendorModel>()
        for (item in items) {
            val model = geminiModel(item) ?: continue
            models[model.id] = model
        }
        val cursor = (root?.get("nextPageToken") as? JsonValue.Text)?.value
        return ParsedPage(models, cursor)
    }

    private fun geminiModel(item: JsonValue): FetchedVendorModel? {
        val obj = item as? JsonValue.Object ?: return null
        if (!supportsGenerateContent(obj)) return null
        val rawId = (obj.get("name") as? JsonValue.Text)?.value ?: return null
        val id = rawId.removePrefix("models/")
        val label = (obj.get("displayName") as? JsonValue.Text)?.value ?: id
        return FetchedVendorModel(
            id,
            label,
            probeU32Field(item, listOf("inputTokenLimit")),
            probeU32Field(item, listOf("outputTokenLimit")),
        )
    }

    private fun supportsGenerateContent(obj: JsonValue.Object): Boolean {
        val methods = (obj.get("supportedGenerationMethods") as? JsonValue.Array)?.values ?: emptyList()
        return methods.any { (it as? JsonValue.Text)?.value == "generateContent" }
    }

    private fun parseData(root: JsonValue.Object?, kind: ModelListEndpoint.Kind): ParsedPage {
        val items = (root?.get("data") as? JsonValue.Array)?.values
            ?: throw ModelListException(ModelListFailureCode.MISSING_MODELS_CONTAINER)
        val models = LinkedHashMap<String, FetchedVendorModel>()
        for (item in items) {
            val model = dataModel(item, kind) ?: continue
            models[model.id] = model
        }
        return ParsedPage(models, dataCursor(root, items))
    }

    private fun dataModel(item: JsonValue, kind: ModelListEndpoint.Kind): FetchedVendorModel? {
        val obj = item as? JsonValue.Object ?: return null
        val id = (obj.get("id") as? JsonValue.Text)?.value ?: return null
        if (kind == ModelListEndpoint.Kind.ANTHROPIC && !isAnthropicModel(obj)) return null
        if (kind == ModelListEndpoint.Kind.OPENAI && isExcludedOpenAiModel(id)) return null
        val label = (obj.get("display_name") as? JsonValue.Text)?.value ?: id
        return FetchedVendorModel(id, label, probeU32Field(item, PROBE_CONTEXT_WINDOW_KEYS), probeU32Field(item, PROBE_MAX_OUTPUT_KEYS))
    }

    private fun isAnthropicModel(obj: JsonValue.Object): Boolean =
        (obj.get("type") as? JsonValue.Text)?.value?.let { it == "model" } != false

    private fun isExcludedOpenAiModel(id: String): Boolean = OPENAI_EXCLUDED_MODEL_NEEDLES.any(id.lowercase(Locale.ROOT)::contains)

    private fun dataCursor(root: JsonValue.Object?, items: List<JsonValue>): String? {
        if ((root?.get("has_more") as? JsonValue.Bool)?.value != true) return null
        return (root?.get("last_id") as? JsonValue.Text)?.value
            ?: (items.lastOrNull() as? JsonValue.Object)?.get("id")?.let { (it as? JsonValue.Text)?.value }
    }

    /** Appends the dialect pagination query to the models URL. */
    fun withQuery(url: String, kind: ModelListEndpoint.Kind, cursor: String?): String {
        val params = mutableListOf<String>()
        when (kind) {
            ModelListEndpoint.Kind.GEMINI -> {
                params += "pageSize=$GEMINI_PAGE_SIZE"
                if (cursor != null) params += "pageToken=${urlEncode(cursor)}"
            }

            ModelListEndpoint.Kind.ANTHROPIC -> {
                params += "limit=$ANTHROPIC_PAGE_LIMIT"
                if (cursor != null) params += "after_id=${urlEncode(cursor)}"
            }

            ModelListEndpoint.Kind.OPENAI -> if (cursor != null) params += "after=${urlEncode(cursor)}"
        }
        if (params.isEmpty()) return url
        val separator = if (url.contains('?')) "&" else "?"
        return "$url${separator}${params.joinToString("&")}"
    }

    private fun urlEncode(value: String): String = java.net.URLEncoder.encode(value, Charsets.UTF_8)
}

class ParsedPage(val models: Map<String, FetchedVendorModel>, val cursor: String?)

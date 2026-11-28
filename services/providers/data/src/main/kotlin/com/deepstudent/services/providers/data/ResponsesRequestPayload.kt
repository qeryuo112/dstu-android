package com.deepstudent.services.providers.data

import com.deepstudent.core.common.json.JsonValue
import java.util.Locale

/**
 * Frozen Responses payload conversion
 * (`providers/openai-responses.ts` convert_to_responses_format_for_endpoint and
 * `openai-codex/protocol.ts` prepare_codex_responses_body at
 * e6b2657fd59014a73f5bbb1d14c2db732cf23e87).
 *
 * Key insertion order matches the frozen serde preserve_order wire order, and
 * removals use indexmap swap_remove semantics (the last entry moves into the
 * removed slot), so the Codex restricted body reaches the wire byte-ordered
 * like the desktop.
 */
internal object ResponsesRequestPayload {
    fun convert(model: String, source: JsonValue.Object, baseUrl: String): JsonValue.Object {
        val sanitized = ResponsesRequestItems.sanitizeOpenaiRequestBody(source)
        val blocks = InputBlocks()
        collectInputBlocks(model, sanitized, baseUrl, blocks)
        if (blocks.items.isEmpty()) {
            blocks.items += JsonValue.obj(
                "role" to JsonValue.text("user"),
                "content" to JsonValue.array(listOf(JsonValue.obj("type" to JsonValue.text("input_text"), "text" to JsonValue.text("")))),
            )
        }
        val developerBreakpoint = blocks.instructions.isNotEmpty() &&
            modelSupportsPromptCacheBreakpoint(model) &&
            isOfficialHost(baseUrl, "api.openai.com")
        if (developerBreakpoint) {
            blocks.items.add(
                0,
                JsonValue.obj(
                    "role" to JsonValue.text("developer"),
                    "content" to JsonValue.array(
                        listOf(
                            JsonValue.obj(
                                "type" to JsonValue.text("input_text"),
                                "text" to JsonValue.text(blocks.instructions.joinToString("\n\n")),
                                "prompt_cache_breakpoint" to JsonValue.obj("mode" to JsonValue.text("explicit")),
                            ),
                        ),
                    ),
                ),
            )
        }
        val payload = basePayload(model, sanitized, blocks.items)
        if (blocks.instructions.isNotEmpty() && !developerBreakpoint) {
            payload["instructions"] = JsonValue.text(blocks.instructions.joinToString("\n\n"))
        }
        applyReasoning(payload, sanitized, model)
        applyInclude(payload, sanitized, model)
        applyDeepseekThinking(payload, sanitized, isOfficialHost(baseUrl, "api.deepseek.com"))
        applyTokensAndSampling(payload, sanitized, isOfficialHost(baseUrl, "api.deepseek.com"), model)
        applyTextFormat(payload, sanitized)
        applyTools(payload, sanitized)
        applyReasoningExtensions(payload, sanitized, baseUrl)
        return JsonValue.Object.from(payload)
    }

    private class InputBlocks {
        val items = mutableListOf<JsonValue>()
        val instructions = mutableListOf<String>()
    }

    private fun collectInputBlocks(model: String, source: JsonValue.Object, baseUrl: String, blocks: InputBlocks) {
        val deepseekOfficial = isOfficialHost(baseUrl, "api.deepseek.com")
        val deepseekHistorySystem = deepseekOfficial &&
            model.trim().lowercase(Locale.ROOT) in DEEPSEEK_HISTORY_SYSTEM_MODELS
        val messages = ResponsesJson.array(ResponsesJson.field(source, "messages")) ?: return
        for (message in messages) {
            val role = ResponsesJson.text(ResponsesJson.field(message, "role")) ?: "user"
            when (role) {
                "system" -> systemBlock(message, role, blocks, deepseekHistorySystem)
                "tool" -> ResponsesRequestItems.toolResultItem(message)?.let(blocks.items::add)
                "assistant" -> assistantBlock(message, role, blocks, deepseekOfficial, deepseekHistorySystem)
                else -> contentBlock(message, role, blocks)
            }
        }
    }

    private fun systemBlock(message: JsonValue, role: String, blocks: InputBlocks, deepseekHistorySystem: Boolean) {
        val content = ResponsesJson.field(message, "content")
        if (deepseekHistorySystem && (blocks.instructions.isNotEmpty() || blocks.items.isNotEmpty())) {
            historySystemItem(content, role, blocks)
            return
        }
        if (content is JsonValue.Text) {
            if (content.value.trim().isNotEmpty()) blocks.instructions += content.value
            return
        }
        collectInstructionParts(content, blocks)
    }

    private fun historySystemItem(content: JsonValue?, role: String, blocks: InputBlocks) {
        val parts = mutableListOf<JsonValue>()
        if (content != null) ResponsesRequestItems.pushMessageParts(parts, role, content)
        blocks.items += JsonValue.obj("role" to JsonValue.text("system"), "content" to JsonValue.array(parts))
    }

    private fun collectInstructionParts(content: JsonValue?, blocks: InputBlocks) {
        for (part in ResponsesJson.array(content) ?: emptyList()) {
            val text = ResponsesJson.text(ResponsesJson.field(part, "text"))
            if (text != null && text.trim().isNotEmpty()) blocks.instructions += text
        }
    }

    private fun assistantBlock(
        message: JsonValue,
        role: String,
        blocks: InputBlocks,
        deepseekOfficial: Boolean,
        deepseekHistorySystem: Boolean,
    ) {
        val reasoningItem = ResponsesJson.field(message, "response_reasoning_item")
        if (reasoningItem != null && ResponsesJson.text(ResponsesJson.field(reasoningItem, "type")) == "reasoning") {
            blocks.items += reasoningItem
        } else if (deepseekOfficial) {
            reasoningContentBlock(message)?.let(blocks.items::add)
        }
        for (item in ResponsesJson.array(ResponsesJson.field(message, "response_web_search_items")) ?: emptyList()) {
            if (isReplayableSearchCall(item, deepseekHistorySystem)) blocks.items += item
        }
        contentBlock(message, role, blocks)
        ResponsesRequestItems.assistantToolCallItems(message).forEach(blocks.items::add)
    }

    /** A present `kind` marks desktop-internal items that stay unsent unless DeepSeek keeps system history. */
    private fun isReplayableSearchCall(item: JsonValue, deepseekHistorySystem: Boolean): Boolean =
        ResponsesJson.text(ResponsesJson.field(item, "type")) == "web_search_call" &&
            (deepseekHistorySystem || ResponsesJson.field(item, "kind") == null)

    private fun reasoningContentBlock(message: JsonValue): JsonValue? {
        val text = ResponsesJson.text(ResponsesJson.field(message, "reasoning_content")) ?: return null
        if (text.isEmpty()) return null
        return JsonValue.obj(
            "type" to JsonValue.text("reasoning"),
            "content" to JsonValue.array(
                listOf(JsonValue.obj("type" to JsonValue.text("reasoning_text"), "text" to JsonValue.text(text))),
            ),
        )
    }

    private fun contentBlock(message: JsonValue, role: String, blocks: InputBlocks) {
        val parts = mutableListOf<JsonValue>()
        val content = ResponsesJson.field(message, "content")
        if (content != null) ResponsesRequestItems.pushMessageParts(parts, role, content)
        if (parts.isNotEmpty()) blocks.items += JsonValue.obj("role" to JsonValue.text(role), "content" to JsonValue.array(parts))
    }

    private fun basePayload(model: String, source: JsonValue.Object, input: List<JsonValue>): LinkedHashMap<String, JsonValue> {
        val stream = ResponsesJson.field(source, "stream")
        val streamEnabled = (stream as? JsonValue.Bool)?.value ?: true
        val payload = LinkedHashMap<String, JsonValue>()
        payload["model"] = JsonValue.text(model)
        payload["input"] = JsonValue.array(input)
        payload["stream"] = JsonValue.bool(streamEnabled)
        payload["store"] = ResponsesJson.field(source, "store") ?: JsonValue.bool(false)
        return payload
    }

    private fun applyReasoning(payload: LinkedHashMap<String, JsonValue>, source: JsonValue.Object, model: String) {
        val reasoning = ResponsesJson.field(source, "reasoning")
        val effort = ResponsesJson.field(source, "reasoning_effort")
        if (reasoning != null) {
            payload["reasoning"] = configuredReasoning(reasoning, effort)
            return
        }
        defaultReasoning(payload, model, effort)
    }

    private fun configuredReasoning(reasoning: JsonValue, effort: JsonValue?): JsonValue {
        var config = reasoning
        val asObject = config as? JsonValue.Object
        if (asObject != null && !asObject.containsKey("effort") && effort != null) {
            config = JsonValue.Object.from(LinkedHashMap(asObject.asMap()).apply { put("effort", effort) })
        }
        val withEffort = config as? JsonValue.Object
        if (withEffort != null && !withEffort.containsKey("summary")) {
            config = JsonValue.Object.from(LinkedHashMap(withEffort.asMap()).apply { put("summary", JsonValue.text("auto")) })
        }
        return config
    }

    private fun defaultReasoning(payload: LinkedHashMap<String, JsonValue>, model: String, effort: JsonValue?) {
        val lower = model.lowercase(Locale.ROOT)
        if (!lower.contains("o1") && !lower.contains("o3") && !lower.contains("gpt-5") && effort == null) return
        val config = LinkedHashMap<String, JsonValue>()
        config["summary"] = JsonValue.text("auto")
        effort?.let { config["effort"] = it }
        payload["reasoning"] = JsonValue.Object.from(config)
    }

    private fun applyInclude(payload: LinkedHashMap<String, JsonValue>, source: JsonValue.Object, model: String) {
        val include = ResponsesJson.field(source, "include")
        if (include != null) {
            payload["include"] = include
            return
        }
        val lower = model.lowercase(Locale.ROOT)
        if (lower.contains("gpt-5") || lower.contains("codex") ||
            lower.startsWith("o1") || lower.startsWith("o3") || lower.startsWith("o4")
        ) {
            payload["include"] = JsonValue.array(listOf(JsonValue.text("reasoning.encrypted_content")))
        }
    }

    /** Frozen lines 428-439: only the explicit enabled/disabled thinking types rewrite reasoning. */
    private fun applyDeepseekThinking(payload: LinkedHashMap<String, JsonValue>, source: JsonValue.Object, deepseekOfficial: Boolean) {
        if (!deepseekOfficial) return
        when (ResponsesJson.text(ResponsesJson.field(ResponsesJson.field(source, "thinking"), "type"))) {
            "disabled" -> payload["reasoning"] = JsonValue.obj("effort" to JsonValue.text("none"))
            "enabled" -> payload["reasoning"] = JsonValue.obj("effort" to deepseekEffort(source))
            else -> Unit
        }
    }

    private fun deepseekEffort(source: JsonValue.Object): JsonValue = firstPresent(
        ResponsesJson.field(source, "reasoning_effort"),
        ResponsesJson.field(ResponsesJson.field(source, "reasoning"), "effort"),
    ) ?: JsonValue.text("high")

    private fun applyTokensAndSampling(
        payload: LinkedHashMap<String, JsonValue>,
        source: JsonValue.Object,
        deepseekOfficial: Boolean,
        model: String,
    ) {
        val maxTokens = firstPresent(
            ResponsesJson.field(source, "max_completion_tokens"),
            ResponsesJson.field(source, "max_total_tokens"),
            ResponsesJson.field(source, "max_tokens"),
        )
        if (maxTokens != null) payload["max_output_tokens"] = maxTokens
        ResponsesJson.field(source, "temperature")?.let { payload["temperature"] = it }
        if (deepseekOfficial) applyOfficialExtras(payload, source, model)
    }

    private fun applyOfficialExtras(payload: LinkedHashMap<String, JsonValue>, source: JsonValue.Object, model: String) {
        ResponsesJson.field(source, "top_p")?.let { payload["top_p"] = it }
        if (model == "deepseek-flash") applyDeepseekFlashOptions(payload, source)
    }

    private fun applyDeepseekFlashOptions(payload: LinkedHashMap<String, JsonValue>, source: JsonValue.Object) {
        for (key in listOf("safety_check", "title")) {
            ResponsesJson.field(source, key)?.let { payload[key] = it }
        }
    }

    private fun applyTextFormat(payload: LinkedHashMap<String, JsonValue>, source: JsonValue.Object) {
        ResponsesJson.field(source, "response_format")?.let {
            payload["text"] = JsonValue.obj("format" to ResponsesRequestItems.convertResponseFormat(it))
        }
        ResponsesJson.field(source, "verbosity")?.let { verbosity ->
            val textConfig = payload["text"] as? JsonValue.Object
            payload["text"] = JsonValue.Object.from(
                LinkedHashMap<String, JsonValue>().apply {
                    textConfig?.let { putAll(it.asMap()) }
                    put("verbosity", verbosity)
                },
            )
        }
    }

    private fun applyTools(payload: LinkedHashMap<String, JsonValue>, source: JsonValue.Object) {
        val tools = ResponsesJson.array(ResponsesJson.field(source, "tools"))
        if (tools != null && tools.isNotEmpty()) payload["tools"] = JsonValue.array(tools.map(ResponsesRequestItems::convertToolDefinition))
        ResponsesJson.field(source, "tool_choice")?.let { choice ->
            ResponsesRequestItems.convertToolChoice(choice)?.let { payload["tool_choice"] = it }
        }
        ResponsesJson.field(source, "parallel_tool_calls")?.let { payload["parallel_tool_calls"] = it }
    }

    private fun applyReasoningExtensions(payload: LinkedHashMap<String, JsonValue>, source: JsonValue.Object, baseUrl: String) {
        if (!preservesProviderReasoningExtensions(baseUrl)) return
        for (key in listOf("thinking", "enable_thinking", "thinking_budget")) {
            ResponsesJson.field(source, key)?.let { payload[key] = it }
        }
    }

    /** providers/openai-responses.ts preservesProviderReasoningExtensions. */
    private fun preservesProviderReasoningExtensions(baseUrl: String): Boolean {
        val host = hostOf(baseUrl) ?: return false
        return host == "dashscope.aliyuncs.com" || host == "dashscope-intl.aliyuncs.com" ||
            host.endsWith(".maas.aliyuncs.com") || host == "qianfan.baidubce.com" || host.endsWith(".volces.com")
    }

    /** openai-codex/protocol.ts prepareCodexResponsesBody restricted Codex account body. */
    fun prepareCodexResponsesBody(body: JsonValue.Object, sessionId: String): JsonValue.Object {
        val payload = LinkedHashMap(body.asMap())
        if ((ResponsesJson.text(payload["model"]).orEmpty()).trim().isEmpty()) {
            throw IllegalArgumentException("codex responses body requires a model")
        }
        if (!payload.containsKey("input")) throw IllegalArgumentException("codex responses body requires input items")
        payload["store"] = JsonValue.bool(false)
        payload["stream"] = JsonValue.bool(true)
        val include = (ResponsesJson.swapRemove(payload, "include") as? JsonValue.Array)?.values.orEmpty().toMutableList()
        if (include.none { it == JsonValue.text("reasoning.encrypted_content") }) {
            include += JsonValue.text("reasoning.encrypted_content")
        }
        payload["include"] = JsonValue.array(include)
        for (key in STRIPPED_CODEX_KEYS) ResponsesJson.swapRemove(payload, key)
        if (!payload.containsKey("prompt_cache_key")) payload["prompt_cache_key"] = JsonValue.text(sessionId)
        return JsonValue.Object.from(payload)
    }

    private fun firstPresent(vararg values: JsonValue?): JsonValue? = values.firstOrNull { it != null }

    internal fun isOfficialHost(baseUrl: String, host: String): Boolean = hostOf(baseUrl) == host

    internal fun hostOf(baseUrl: String): String? = runCatching {
        java.net.URI(baseUrl.trim()).host?.lowercase(Locale.ROOT)
    }.getOrNull()

    /** providers/openai-responses.ts modelSupportsPromptCacheBreakpoint (u32 parse). */
    internal fun modelSupportsPromptCacheBreakpoint(model: String): Boolean {
        val segment = model.trim().lowercase(Locale.ROOT).split('/').last()
        if (!segment.startsWith("gpt-")) return false
        val rest = segment.substring(4)
        val majorDigits = rest.takeWhile { it in '0'..'9' }
        val major = majorDigits.toLongOrNull()?.takeIf { it in 0..U32_MAX } ?: return false
        val afterMajor = rest.substring(majorDigits.length)
        return if (major > 5) supportsOpenBreakpoint(afterMajor) else major == 5L && supportsGpt5MinorBreakpoint(afterMajor)
    }

    private fun supportsOpenBreakpoint(afterMajor: String): Boolean = afterMajor.isEmpty() ||
        afterMajor.startsWith("-") ||
        afterMajor.startsWith("_") ||
        (afterMajor.startsWith(".") && afterMajor.length > 1 && afterMajor[1] in '0'..'9')

    private fun supportsGpt5MinorBreakpoint(afterMajor: String): Boolean {
        if (!afterMajor.startsWith(".")) return false
        val minorPart = afterMajor.substring(1)
        val minorDigits = minorPart.takeWhile { it in '0'..'9' }
        val minor = minorDigits.toLongOrNull()?.takeIf { it in 0..U32_MAX } ?: return false
        val suffix = minorPart.substring(minorDigits.length)
        return minor >= 6 && (suffix.isEmpty() || suffix.startsWith("-") || suffix.startsWith("_"))
    }

    private val DEEPSEEK_HISTORY_SYSTEM_MODELS = setOf("deepseek-flash", "deepseek-v4-flash", "deepseek-v4-flash-vision-exp")
    private val STRIPPED_CODEX_KEYS = listOf(
        "max_output_tokens",
        "max_completion_tokens",
        "max_tokens",
        "max_total_tokens",
        "temperature",
        "top_p",
    )
    private const val U32_MAX = 4_294_967_295L
}

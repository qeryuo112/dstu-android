package com.deepstudent.services.providers.data

import com.deepstudent.core.common.json.JsonValue
import java.math.BigDecimal
import java.math.MathContext

/**
 * Frozen wire conversion
 * (`providers/anthropic.ts` convert_openai_to_anthropic at
 * e6b2657fd59014a73f5bbb1d14c2db732cf23e87). Key insertion order matches the
 * frozen serde wire order, and presence probes precede casts so a present
 * non-integer behaves exactly like the desktop's `or_else` chains.
 *
 * The final `cache_control` marker always rides the request root; system and
 * tool breakpoints share the remaining three slots, and the earliest markers
 * drop first on overflow.
 */
internal object AnthropicWireConvert {
    fun convert(model: String, body: JsonValue.Object, imageSource: AnthropicImageSource?): JsonValue.Object {
        val stream = (body["stream"] as? JsonValue.Bool)?.value ?: false
        val adaptive = claudeGeneration(model) == ClaudeGeneration.ADAPTIVE
        val thinking = rewrittenThinking(body, adaptive)
        val hasThinking = thinking.wireType == "enabled" || thinking.wireType == "adaptive"

        val contents = convertContents(body, imageSource)
        val request = AnthropicBlock()
        request["model"] = JsonValue.text(model)
        request["max_tokens"] = JsonValue.integer(maxTokens(body).toLong())
        appendContents(request, contents)
        resolvedToolChoice(body, contents.tools, hasThinking)?.let { request["tool_choice"] = it }
        appendSampling(body, request, hasThinking, adaptive)
        appendStopSequences(body, request)
        if (stream) request["stream"] = JsonValue.bool(true)
        thinking.wire?.let { request["thinking"] = it }
        appendOutputConfig(body, request, thinking.derivedEffort)
        request["cache_control"] = anthropicEphemeral()
        return request.freeze()
    }

    private class Contents(val system: List<AnthropicBlock>, val messages: List<AnthropicMessage>, val tools: List<AnthropicBlock>)

    private fun convertContents(body: JsonValue.Object, imageSource: AnthropicImageSource?): Contents {
        val system = mutableListOf<AnthropicBlock>()
        val messages = mutableListOf<AnthropicMessage>()
        for (item in ResponsesJson.array(body["messages"]).orEmpty()) {
            appendSourceMessage(ResponsesJson.obj(item) ?: continue, imageSource, system, messages)
        }
        if (system.isNotEmpty() && system.none { it.containsKey("cache_control") }) {
            system.last()["cache_control"] = anthropicEphemeral()
        }
        val tools = convertedTools(body)
        enforceAnthropicCacheBreakpointBudget(tools, system)
        return Contents(system, messages, tools)
    }

    private fun appendContents(request: AnthropicBlock, contents: Contents) {
        request["messages"] = frozenMessages(contents.messages)
        if (contents.system.isNotEmpty()) request["system"] = frozenBlocks(contents.system)
        if (contents.tools.isNotEmpty()) request["tools"] = frozenBlocks(contents.tools)
    }

    /** Rust `or_else` chain over max_tokens aliases; 1024 only when all are absent. */
    private fun maxTokens(body: JsonValue.Object): Int {
        val key = listOf("max_tokens", "max_completion_tokens", "max_total_tokens").firstOrNull(body::containsKey)
        return key?.let { body.i32At(it) } ?: 1_024
    }

    private class AnthropicThinking(val wire: JsonValue?, val wireType: String?, val derivedEffort: String?)

    /**
     * Verbatim passthrough for manual thinking; the adaptive generation rewrites
     * an enabled block to `{type: adaptive}` and derives the effort level.
     */
    private fun rewrittenThinking(body: JsonValue.Object, adaptive: Boolean): AnthropicThinking {
        val raw = body["thinking"] ?: return AnthropicThinking(null, null, null)
        val objectForm = ResponsesJson.obj(raw)
        if (!adaptive) {
            return AnthropicThinking(raw, ResponsesJson.text(objectForm?.get("type")), null)
        }
        if (objectForm != null && ResponsesJson.text(objectForm["type"]) == "enabled") {
            val effort = objectForm.i32At("budget_tokens")?.let { mapBudgetTokensToEffort(it.toLong()) }
            val block = AnthropicBlock()
            block["type"] = JsonValue.text("adaptive")
            return AnthropicThinking(block.freeze(), "adaptive", effort)
        }
        return AnthropicThinking(raw, ResponsesJson.text(objectForm?.get("type")), null)
    }

    private fun appendSourceMessage(
        message: JsonValue.Object,
        imageSource: AnthropicImageSource?,
        systemBlocks: MutableList<AnthropicBlock>,
        messages: MutableList<AnthropicMessage>,
    ) {
        when (ResponsesJson.text(message["role"]).orEmpty()) {
            "system", "developer" -> systemBlocks += extractAnthropicSystemBlocks(message)
            "user" -> mergeConverted(messages, convertAnthropicUserMessage(message, imageSource))
            "assistant" -> mergeConverted(messages, convertAnthropicAssistantMessage(message))
            "tool", "function" -> convertAnthropicToolResultMessage(message)?.let(messages::add)
        }
    }

    private fun mergeConverted(messages: MutableList<AnthropicMessage>, converted: AnthropicMessage?) {
        converted ?: return
        val last = messages.lastOrNull()
        if (last != null && last.role == converted.role) {
            mergeAnthropicTextContent(last.blocks, converted.blocks)
            return
        }
        messages += converted
    }

    /** Auto tail marker unless the caller supplied a tool marker of their own. */
    private fun convertedTools(body: JsonValue.Object): MutableList<AnthropicBlock> {
        val converted = mutableListOf<AnthropicBlock>()
        for (tool in ResponsesJson.array(body["tools"]) ?: emptyList()) {
            convertAnthropicToolDefinition(tool)?.let(converted::add)
        }
        if (converted.isNotEmpty() && converted.none { it.containsKey("cache_control") }) {
            converted.last()["cache_control"] = anthropicEphemeral()
        }
        return converted
    }

    /** Explicit choice wins; absent choice with tools defaults to auto; thinking forces auto/none. */
    private fun resolvedToolChoice(body: JsonValue.Object, tools: List<AnthropicBlock>, hasThinking: Boolean): JsonValue.Object? {
        val declared = body["tool_choice"]
        val choice = convertAnthropicToolChoice(declared)
            ?: (if (declared == null && tools.isNotEmpty()) JsonValue.obj("type" to JsonValue.text("auto")) else null)
            ?: return null
        if (hasThinking && tools.isNotEmpty()) {
            val type = ResponsesJson.text(choice["type"]).orEmpty()
            if (type != "auto" && type != "none") return JsonValue.obj("type" to JsonValue.text("auto"))
        }
        return choice
    }

    /** Sampling survives only without thinking or adaptive rewriting (f32-shortest decimals). */
    private fun appendSampling(body: JsonValue.Object, request: AnthropicBlock, hasThinking: Boolean, adaptive: Boolean) {
        if (hasThinking || adaptive) return
        val rawTemperature = (body["temperature"] as? JsonValue.Number)?.literal?.toDoubleOrNull()
        val rawTopP = (body["top_p"] as? JsonValue.Number)?.literal?.toDoubleOrNull()
        if (rawTemperature != null) {
            request["temperature"] = JsonValue.Number.raw(f32Shortest(rawTemperature).toString())
        } else if (rawTopP != null) {
            request["top_p"] = JsonValue.Number.raw(f32Shortest(rawTopP).toString())
        }
        body.i32At("top_k")?.let { request["top_k"] = JsonValue.integer(it.toLong()) }
    }

    private fun appendStopSequences(body: JsonValue.Object, request: AnthropicBlock) {
        when (val stop = body["stop"]) {
            is JsonValue.Text -> if (stop.value.isNotEmpty()) {
                request["stop_sequences"] = JsonValue.array(listOf(JsonValue.text(stop.value)))
            }

            is JsonValue.Array -> {
                val sequences = stop.values.mapNotNull { ResponsesJson.text(it) }
                if (sequences.isNotEmpty()) request["stop_sequences"] = JsonValue.array(sequences.map(JsonValue::text))
            }

            else -> Unit
        }
    }

    /** Effort and structured output land together in `output_config`. */
    private fun appendOutputConfig(body: JsonValue.Object, request: AnthropicBlock, derivedEffort: String?) {
        val effort = ResponsesJson.text(body["effort"]) ?: derivedEffort
        val format = convertAnthropicResponseFormat(body["response_format"])
        if (effort == null && format == null) return
        val config = AnthropicBlock()
        effort?.let { config["effort"] = JsonValue.text(it) }
        format?.let { config["format"] = it }
        request["output_config"] = config.freeze()
    }

    private fun frozenBlocks(blocks: List<AnthropicBlock>): JsonValue.Array = JsonValue.array(blocks.map { it.freeze() })

    private fun frozenMessages(messages: List<AnthropicMessage>): JsonValue.Array = JsonValue.array(
        messages.map { message ->
            AnthropicBlock().apply {
                put("role", JsonValue.text(message.role))
                put("content", frozenBlocks(message.blocks))
            }.freeze()
        },
    )

    /** Shortest decimal that round-trips through f32, matching serde_json's f32 output. */
    internal fun f32Shortest(value: Double): Double {
        val target = value.toFloat().toDouble()
        for (precision in 1..9) {
            val candidate = BigDecimal(target).round(MathContext(precision)).toDouble()
            if (candidate.toFloat().toDouble() == target) return candidate
        }
        return target
    }
}

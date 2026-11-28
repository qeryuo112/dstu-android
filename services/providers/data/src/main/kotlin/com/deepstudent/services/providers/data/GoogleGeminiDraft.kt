package com.deepstudent.services.providers.data

import com.deepstudent.core.common.json.JsonValue

/** Build failures mirror the frozen AdapterError kinds; details stay out of diagnostics. */
internal class GoogleGeminiBuildException(val kind: String, detail: String) : Exception("$kind: $detail")

/**
 * Same-origin target pieces recovered from the resolved endpoint
 * (`scheme://host[:port]`, the path before the version segment, and the
 * route-resolved `v1`/`v1beta`). The converter never changes the origin.
 */
internal class GoogleGeminiRoute(val origin: String, val rootPath: String, val version: String)

/**
 * Strict OpenAI request shape (serde semantics of the frozen converter) and the
 * OpenAI-to-Gemini draft conversion (`convert_openai_to_gemini`).
 */
internal object OpenAiShape {
    fun parse(body: JsonValue.Object): Shape {
        body.requiredText("model")
        return Shape(
            messages = body.requiredArray("messages").values.map(::message),
            generation = generation(body),
            tools = body.optionalValue("tools")?.let(::tools),
            toolChoice = body.optionalValue("tool_choice"),
        )
    }

    private fun generation(body: JsonValue.Object) = Generation(
        temperature = body.number("temperature"),
        topP = body.number("top_p"),
        maxTokens = body.i32("max_tokens"),
        frequencyPenalty = body.number("frequency_penalty"),
        presencePenalty = body.number("presence_penalty"),
        stop = body.optionalValue("stop")?.let(::stop),
        responseFormat = body.optionalValue("response_format"),
    )

    class Shape(val messages: List<Message>, val generation: Generation, val tools: List<Tool>?, val toolChoice: JsonValue?)

    class Generation(
        val temperature: Double?,
        val topP: Double?,
        val maxTokens: Long?,
        val frequencyPenalty: Double?,
        val presencePenalty: Double?,
        val stop: List<String>?,
        val responseFormat: JsonValue?,
    )

    private fun JsonValue.Object.optionalValue(key: String): JsonValue? = this[key]?.takeUnless { it == JsonValue.Null }

    class Message(
        val role: String,
        val content: Content?,
        val name: String?,
        val toolCalls: List<ToolCall>?,
        val toolCallId: String?,
        val thoughtSignature: String?,
    )

    sealed interface Content {
        data class Text(val text: String) : Content
        data class Parts(val parts: List<Part>) : Content
    }

    sealed interface Part {
        data class Text(val text: String) : Part
        data class Image(val url: String) : Part
        data object Unsupported : Part
    }

    class ToolCall(val id: String, val type: String, val name: String, val arguments: String)

    class Tool(val type: String, val name: String, val description: String?, val parameters: JsonValue)

    private fun message(value: JsonValue): Message {
        val body = value as? JsonValue.Object ?: fail("invalid type in `messages`")
        val role = body.requiredText("role")
        val rawContent = body["content"]
        val content = when (rawContent) {
            null, JsonValue.Null -> null
            is JsonValue.Text -> Content.Text(rawContent.value)
            is JsonValue.Array -> Content.Parts(rawContent.values.map(::part))
            else -> fail("invalid type in `messages[].content`")
        }
        val toolCalls = body["tool_calls"]?.takeIf { it !is JsonValue.Null }?.let { raw ->
            (raw as? JsonValue.Array ?: fail("invalid type for field `tool_calls`")).values.map(::toolCall)
        }
        return Message(
            role = role,
            content = content,
            name = body.text("name"),
            toolCalls = toolCalls,
            toolCallId = body.text("tool_call_id"),
            thoughtSignature = body.text("thought_signature"),
        )
    }

    private fun part(value: JsonValue): Part {
        val body = value as? JsonValue.Object ?: fail("invalid type in `messages[].content`")
        val type = (body["type"] as? JsonValue.Text)?.value ?: fail("invalid type in `messages[].content`")
        return when (type) {
            "text" -> Part.Text(body.requiredText("text"))
            "image_url" -> imagePart(body)
            else -> Part.Unsupported
        }
    }

    private fun imagePart(body: JsonValue.Object): Part.Image {
        val image = body["image_url"] as? JsonValue.Object ?: fail("missing field `image_url`")
        val detail = body.optionalValue("detail")
        if (detail != null && detail !is JsonValue.Text) fail("invalid type for field `detail`")
        return Part.Image(image.requiredText("url"))
    }

    private fun toolCall(value: JsonValue): ToolCall {
        val body = value as? JsonValue.Object ?: fail("invalid type in `tool_calls`")
        val function = body["function"] as? JsonValue.Object ?: fail("missing field `function`")
        return ToolCall(
            id = body.requiredText("id"),
            type = body.requiredText("type"),
            name = function.requiredText("name"),
            arguments = function.requiredText("arguments"),
        )
    }

    private fun tools(value: JsonValue): List<Tool> {
        val array = value as? JsonValue.Array ?: fail("invalid type for field `tools`")
        return array.values.map { raw ->
            val body = raw as? JsonValue.Object ?: fail("invalid type in `tools`")
            val function = body["function"] as? JsonValue.Object ?: fail("missing field `function`")
            if (!function.containsKey("parameters")) fail("missing field `parameters`")
            Tool(
                type = body.requiredText("type"),
                name = function.requiredText("name"),
                description = function.text("description"),
                parameters = checkNotNull(function["parameters"]),
            )
        }
    }

    private fun stop(value: JsonValue): List<String> = when (value) {
        is JsonValue.Text -> listOf(value.value)
        is JsonValue.Array -> value.values.map { (it as? JsonValue.Text)?.value ?: fail("invalid type for field `stop`") }
        else -> fail("invalid type for field `stop`")
    }

    private fun JsonValue.Object.requiredArray(key: String): JsonValue.Array =
        (this[key] as? JsonValue.Array) ?: fail("missing field `$key`")

    private fun JsonValue.Object.requiredText(key: String): String = (this[key] as? JsonValue.Text)?.value ?: fail("missing field `$key`")

    private fun JsonValue.Object.text(key: String): String? = (this[key] as? JsonValue.Text)?.value

    private fun JsonValue.Object.number(key: String): Double? = when (val raw = this[key]) {
        null, JsonValue.Null -> null

        is JsonValue.Number -> raw.asLongOrNull()?.toDouble() ?: raw.literal.toDoubleOrNull()
            ?: fail("invalid type for field `$key`")

        else -> fail("invalid type for field `$key`")
    }

    private fun JsonValue.Object.i32(key: String): Long? {
        val raw = number(key) ?: return null
        if (raw != Math.rint(raw) || raw < -2_147_483_648.0 || raw > 2_147_483_647.0) fail("invalid type for field `$key`")
        return raw.toLong()
    }

    private fun fail(detail: String): Nothing =
        throw GoogleGeminiConverter.buildFailure("serialization_error", "Failed to parse OpenAI request: $detail")
}

/** Synthetic `call-<uuid>` ids never go back to the API (frozen `is_synthetic_tool_call_id`). */
internal fun isSyntheticToolCallId(id: String): Boolean {
    if (!id.startsWith("call-")) return false
    var candidate = id.removePrefix("call-")
    if (candidate.geminiAsciiLower().startsWith("urn:uuid:")) candidate = candidate.substring("urn:uuid:".length)
    if (candidate.startsWith("{") || candidate.startsWith("|")) candidate = candidate.substring(1)
    if (candidate.endsWith("}") || candidate.endsWith("|")) candidate = candidate.dropLast(1)
    val hex = candidate.replace("-", "")
    return hex.length == 32 && hex.all(::isHexDigit)
}

private fun isHexDigit(char: Char): Boolean = char in '0'..'9' || char in 'a'..'f' || char in 'A'..'F'

/** Frozen `native_tool_call_id`: blank and synthetic ids are not sent back. */
internal fun nativeToolCallId(id: String): String? {
    val trimmed = id.trim()
    if (trimmed.isEmpty() || isSyntheticToolCallId(trimmed)) return null
    return trimmed
}

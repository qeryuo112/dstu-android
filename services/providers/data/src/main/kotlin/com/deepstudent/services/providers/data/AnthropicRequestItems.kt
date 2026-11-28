package com.deepstudent.services.providers.data

import com.deepstudent.core.common.json.JsonValue
import java.util.UUID

/*
 * Ordered Anthropic message and tool conversion
 * (`providers/anthropic.ts` convert_openai_to_anthropic helpers at
 * e6b2657fd59014a73f5bbb1d14c2db732cf23e87). Accessors keep the frozen
 * Rust-Option presence semantics: a present-but-null field is [JsonValue.Null],
 * a missing one reads as absent, and a wrong shape behaves like absence.
 *
 * User text keeps empty-text drops, images inline through the injected source
 * only, and assistant thinking/signature/redacted blocks pass through verbatim
 * so a same-target turn can replay its private state.
 */

/** Still-unfrozen wire message; consecutive same-role content merges in place. */
internal class AnthropicMessage(val role: String, val blocks: MutableList<AnthropicBlock>)

/** `providers/mod.rs:3403 sanitize_tool_name_for_anthropic`. */
internal fun sanitizeAnthropicToolName(name: String): String = buildString {
    for (char in name) {
        append(
            if (char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' || char == '_' || char == '-') char else '_',
        )
    }
}

/** `restore_tool_name_from_anthropic`: names already survive verbatim. */
internal fun restoreAnthropicToolName(name: String): String = name

/** `providers/mod.rs:3421 convert_tool_definition` with schema defaulting. */
internal fun convertAnthropicToolDefinition(value: JsonValue): AnthropicBlock? {
    if ((ResponsesJson.text(ResponsesJson.field(value, "type")) ?: "function") != "function") return null
    val fn = ResponsesJson.obj(ResponsesJson.field(value, "function")) ?: return null
    val rawName = ResponsesJson.text(fn["name"]) ?: return null
    val block = AnthropicBlock()
    block["name"] = JsonValue.text(sanitizeAnthropicToolName(rawName))
    ResponsesJson.text(fn["description"])?.let { block["description"] = JsonValue.text(it) }
    block["input_schema"] = anthropicToolInputSchema(fn)
    (value as? JsonValue.Object)?.get("cache_control")?.let { block["cache_control"] = it }
    return block
}

/** Missing parameters default to an empty object schema; typed schemas gain `type: object`. */
private fun anthropicToolInputSchema(fn: JsonValue.Object): JsonValue {
    val inputSchema = ResponsesJson.field(fn, "parameters") ?: defaultAnthropicSchema()
    val schemaObject = ResponsesJson.obj(inputSchema) ?: return inputSchema
    return if (schemaObject.containsKey("type")) inputSchema else withSchemaType(schemaObject, "object")
}

private fun defaultAnthropicSchema(): JsonValue.Object = JsonValue.obj(
    "type" to JsonValue.text("object"),
    "properties" to JsonValue.Object.EMPTY,
)

private fun withSchemaType(schema: JsonValue.Object, type: String): JsonValue.Object =
    JsonValue.Object.from(LinkedHashMap(schema.asMap()).apply { put("type", JsonValue.text(type)) })

/** `providers/mod.rs:3462 convert_tool_choice` (string, named and bare-function forms). */
internal fun convertAnthropicToolChoice(choice: JsonValue?): JsonValue.Object? {
    if (choice == null) return null
    if (choice is JsonValue.Text) return anthropicTextToolChoice(choice.value)
    val obj = ResponsesJson.obj(choice) ?: return null
    return anthropicObjectToolChoice(obj)
}

private fun anthropicTextToolChoice(value: String): JsonValue.Object? = when (value) {
    "auto", "none", "any" -> JsonValue.obj("type" to JsonValue.text(value))
    else -> null
}

private fun anthropicObjectToolChoice(obj: JsonValue.Object): JsonValue.Object? {
    val type = ResponsesJson.text(obj["type"])
    if (type in setOf("auto", "none", "any")) return JsonValue.obj("type" to JsonValue.text(checkNotNull(type)))
    if (type == "function" || type == "tool") return anthropicNamedToolChoice(obj)
    val functionName = ResponsesJson.text(obj["function"])
    return functionName?.let { name -> anthropicNamedChoice(name) }
}

private fun anthropicNamedToolChoice(obj: JsonValue.Object): JsonValue.Object? {
    val name = ResponsesJson.text(obj["name"]) ?: ResponsesJson.text(ResponsesJson.field(obj["function"], "name"))
    return name?.let(::anthropicNamedChoice)
}

private fun anthropicNamedChoice(name: String): JsonValue.Object =
    JsonValue.obj("type" to JsonValue.text("tool"), "name" to JsonValue.text(name))

/** `providers/mod.rs:3535 convert_response_format_for_anthropic`. */
internal fun convertAnthropicResponseFormat(value: JsonValue?): JsonValue? {
    if (value == null) return null
    val obj = ResponsesJson.obj(value) ?: return value
    return when (ResponsesJson.text(obj["type"]).orEmpty()) {
        "json_object" -> JsonValue.obj("type" to JsonValue.text("json"))
        "json_schema" -> anthropicJsonSchemaFormat(obj)
        else -> value
    }
}

private fun anthropicJsonSchemaFormat(obj: JsonValue.Object): JsonValue {
    val wrapper = obj["json_schema"] ?: return JsonValue.obj("type" to JsonValue.text("json"))
    val wrapperObject = ResponsesJson.obj(wrapper)
    val schema = wrapperObject?.takeIf { it.containsKey("schema") }?.get("schema") ?: wrapper
    return JsonValue.obj("type" to JsonValue.text("json_schema"), "schema" to schema)
}

/** `providers/mod.rs:3094 extract_system_text_blocks` with block-level marker passthrough. */
internal fun extractAnthropicSystemBlocks(message: JsonValue.Object): MutableList<AnthropicBlock> {
    val blocks = mutableListOf<AnthropicBlock>()
    appendSystemContent(message["content"], blocks)
    return blocks
}

private fun appendSystemContent(content: JsonValue?, blocks: MutableList<AnthropicBlock>) {
    if (content == null) return
    if (content is JsonValue.Text) {
        blocks += anthropicTextBlock(content.value)
        return
    }
    for (part in ResponsesJson.array(content) ?: emptyList()) appendSystemPart(part, blocks)
}

private fun appendSystemPart(part: JsonValue, blocks: MutableList<AnthropicBlock>) {
    if (ResponsesJson.text(ResponsesJson.field(part, "type")) != "text") return
    val text = ResponsesJson.text(ResponsesJson.field(part, "text")) ?: return
    val block = anthropicTextBlock(text)
    (part as? JsonValue.Object)?.get("cache_control")?.let { block["cache_control"] = it }
    blocks += block
}

internal fun anthropicTextBlock(text: String): AnthropicBlock {
    val block = AnthropicBlock()
    block["type"] = JsonValue.text("text")
    block["text"] = JsonValue.text(text)
    return block
}

/** `providers/mod.rs:3119 convert_user_message`: empty text drops, images inline. */
internal fun convertAnthropicUserMessage(message: JsonValue.Object, imageSource: AnthropicImageSource?): AnthropicMessage? {
    val blocks = mutableListOf<AnthropicBlock>()
    appendUserContent(message["content"], blocks, imageSource)
    return if (blocks.isEmpty()) null else AnthropicMessage("user", blocks)
}

private fun appendUserContent(content: JsonValue?, blocks: MutableList<AnthropicBlock>, imageSource: AnthropicImageSource?) {
    when {
        content == null -> Unit
        content is JsonValue.Text -> if (content.value.isNotEmpty()) blocks += anthropicTextBlock(content.value)
        else -> for (part in ResponsesJson.array(content) ?: emptyList()) appendUserPart(part, blocks, imageSource)
    }
}

private fun appendUserPart(part: JsonValue, blocks: MutableList<AnthropicBlock>, imageSource: AnthropicImageSource?) {
    when (ResponsesJson.text(ResponsesJson.field(part, "type"))) {
        "text" -> {
            val text = ResponsesJson.text(ResponsesJson.field(part, "text"))
            if (!text.isNullOrEmpty()) blocks += anthropicTextBlock(text)
        }

        "image_url" -> anthropicImageBlock(part, imageSource)?.let(blocks::add)
    }
}

/** `providers/mod.rs:3509 create_base64_payload` (data URL parse or injected lookup). */
internal fun anthropicImageBlock(part: JsonValue, imageSource: AnthropicImageSource?): AnthropicBlock? {
    val url = ResponsesJson.text(ResponsesJson.field(ResponsesJson.field(part, "image_url"), "url")) ?: return null
    val payload = if (url.startsWith("data:")) anthropicDataUrlPayload(url) else anthropicRemotePayload(url, imageSource)
    payload ?: return null
    val block = AnthropicBlock()
    block["type"] = JsonValue.text("image")
    block["source"] = JsonValue.obj(
        "type" to JsonValue.text("base64"),
        "media_type" to JsonValue.text(payload.first),
        "data" to JsonValue.text(payload.second),
    )
    return block
}

private fun anthropicDataUrlPayload(url: String): Pair<String, String>? {
    val comma = url.indexOf(',')
    if (comma < 0) return null
    val header = url.substring(0, comma)
    val mediaType = header.replace(Regex("^(?:data:)+"), "").replace(Regex("(?:;base64)+$"), "")
    return mediaType to url.substring(comma + 1)
}

private fun anthropicRemotePayload(url: String, imageSource: AnthropicImageSource?): Pair<String, String>? {
    if (!url.startsWith("http://") && !url.startsWith("https://")) return null
    val fetched = imageSource?.let { source -> runCatching { source.fetch(url) }.getOrNull() } ?: return null
    return (fetched.mediaType.ifEmpty { "application/octet-stream" }) to fetched.base64
}

/** `providers/mod.rs:3170 convert_assistant_message` keeps thinking/signature/redacted blocks. */
internal fun convertAnthropicAssistantMessage(message: JsonValue.Object): AnthropicMessage? {
    val blocks = mutableListOf<AnthropicBlock>()
    appendAssistantContent(message["content"], blocks)
    appendAssistantToolCalls(message["tool_calls"], blocks)
    if (blocks.isEmpty()) return null
    return AnthropicMessage("assistant", blocks)
}

private fun appendAssistantContent(content: JsonValue?, blocks: MutableList<AnthropicBlock>) {
    if (content == null) return
    if (content is JsonValue.Text) {
        if (content.value.isNotEmpty()) blocks += anthropicTextBlock(content.value)
        return
    }
    for (part in ResponsesJson.array(content) ?: emptyList()) appendAssistantPart(part, blocks)
}

private fun appendAssistantPart(part: JsonValue, blocks: MutableList<AnthropicBlock>) {
    when (ResponsesJson.text(ResponsesJson.field(part, "type")).orEmpty()) {
        "thinking" -> appendThinkingPart(part, blocks)
        "redacted_thinking" -> appendRedactedPart(part, blocks)
        "text" -> appendAssistantTextPart(part, blocks)
        "tool_use" -> appendInlineToolUse(part, blocks)
    }
}

private fun appendThinkingPart(part: JsonValue, blocks: MutableList<AnthropicBlock>) {
    val thinking = ResponsesJson.text(ResponsesJson.field(part, "thinking")).orEmpty()
    val signature = ResponsesJson.text(ResponsesJson.field(part, "signature")).orEmpty()
    if (thinking.isNotEmpty() || signature.isNotEmpty()) {
        blocks += AnthropicBlock().apply {
            put("type", JsonValue.text("thinking"))
            put("thinking", JsonValue.text(thinking))
            if (signature.isNotEmpty()) put("signature", JsonValue.text(signature))
        }
    }
}

private fun appendRedactedPart(part: JsonValue, blocks: MutableList<AnthropicBlock>) {
    ResponsesJson.text(ResponsesJson.field(part, "data"))?.let { data ->
        blocks += AnthropicBlock().apply {
            put("type", JsonValue.text("redacted_thinking"))
            put("data", JsonValue.text(data))
        }
    }
}

private fun appendAssistantTextPart(part: JsonValue, blocks: MutableList<AnthropicBlock>) {
    ResponsesJson.text(ResponsesJson.field(part, "text"))?.takeIf { it.isNotEmpty() }?.let {
        blocks += anthropicTextBlock(it)
    }
}

private fun appendInlineToolUse(part: JsonValue, blocks: MutableList<AnthropicBlock>) {
    val id = ResponsesJson.text(ResponsesJson.field(part, "id")) ?: "tool_call_${UUID.randomUUID()}"
    val name = ResponsesJson.text(ResponsesJson.field(part, "name")).orEmpty()
    val holder = ResponsesJson.obj(part)
    val input = holder?.get("input")?.takeIf { holder.containsKey("input") } ?: JsonValue.Object.EMPTY
    blocks += anthropicToolUseBlock(id, name, input)
}

private fun anthropicToolUseBlock(id: String, name: String, input: JsonValue): AnthropicBlock = AnthropicBlock().apply {
    put("type", JsonValue.text("tool_use"))
    put("id", JsonValue.text(id))
    put("name", JsonValue.text(name))
    put("input", input)
}

private fun appendAssistantToolCalls(toolCalls: JsonValue?, blocks: MutableList<AnthropicBlock>) {
    for (toolCall in ResponsesJson.array(toolCalls) ?: emptyList()) {
        val fn = ResponsesJson.field(toolCall, "function") ?: continue
        val name = ResponsesJson.text(ResponsesJson.field(fn, "name")).orEmpty()
        val id = ResponsesJson.text(ResponsesJson.field(toolCall, "id")) ?: "tool_call_${UUID.randomUUID()}"
        val rawArguments = ResponsesJson.text(ResponsesJson.field(fn, "arguments")) ?: "{}"
        val input = runCatching { JsonValue.parse(rawArguments) }.getOrNull() ?: JsonValue.Object.EMPTY
        blocks += anthropicToolUseBlock(id, name, input)
    }
}

/** `providers/mod.rs:3322 convert_tool_result_message`. */
internal fun convertAnthropicToolResultMessage(message: JsonValue.Object): AnthropicMessage? {
    val toolUseId = ResponsesJson.text(message["tool_call_id"]).orEmpty()
    if (toolUseId.isEmpty()) return null
    val block = anthropicToolResultBlock(toolUseId, message)
    return AnthropicMessage("user", mutableListOf(block))
}

private fun anthropicToolResultBlock(toolUseId: String, message: JsonValue.Object): AnthropicBlock {
    val parts = anthropicToolResultParts(message["content"])
    val block = AnthropicBlock()
    block["type"] = JsonValue.text("tool_result")
    block["tool_use_id"] = JsonValue.text(toolUseId)
    if (parts.isNotEmpty()) block["content"] = JsonValue.array(parts.map { it.freeze() })
    (message["is_error"] as? JsonValue.Bool)?.let { block["is_error"] = it }
    return block
}

private fun anthropicToolResultParts(content: JsonValue?): MutableList<AnthropicBlock> {
    val parts = mutableListOf<AnthropicBlock>()
    when (content) {
        is JsonValue.Text -> if (content.value.isNotEmpty()) parts += anthropicTextBlock(content.value)
        else -> for (item in ResponsesJson.array(content) ?: emptyList()) appendToolResultText(item, parts)
    }
    return parts
}

private fun appendToolResultText(item: JsonValue, parts: MutableList<AnthropicBlock>) {
    if (ResponsesJson.text(ResponsesJson.field(item, "type")) != "text") return
    val text = ResponsesJson.text(ResponsesJson.field(item, "text"))
    if (!text.isNullOrEmpty()) parts += anthropicTextBlock(text)
}

/** `providers/mod.rs:3370 merge_text_content`: same-role text joins with a blank line. */
internal fun mergeAnthropicTextContent(existing: MutableList<AnthropicBlock>, incoming: List<AnthropicBlock>) {
    for (block in incoming) {
        val text = ResponsesJson.text(block["text"])
        if (ResponsesJson.text(block["type"]) == "text" && text != null) {
            val target = existing.firstOrNull { ResponsesJson.text(it["type"]) == "text" }
            if (target != null) {
                target["text"] = JsonValue.text(ResponsesJson.text(target["text"]).orEmpty() + "\n\n" + text)
                continue
            }
        }
        existing += block
    }
}

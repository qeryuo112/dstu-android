package com.deepstudent.services.providers.data

import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.services.providers.api.ProviderMessage

/**
 * JSON readers with the frozen Rust-Option presence semantics
 * (`llm-providers/src/json.ts` field/string/array/object): a present-but-null
 * field stays [JsonValue.Null], a missing one becomes Kotlin null.
 */
internal object ResponsesJson {
    fun field(value: JsonValue?, key: String): JsonValue? = (value as? JsonValue.Object)?.get(key)

    fun text(value: JsonValue?): String? = (value as? JsonValue.Text)?.value

    fun array(value: JsonValue?): List<JsonValue>? = (value as? JsonValue.Array)?.values

    fun obj(value: JsonValue?): JsonValue.Object? = value as? JsonValue.Object

    /**
     * indexmap swap_remove (serde_json preserve_order): the last entry moves
     * into the removed slot (`llm-providers/src/json_map.ts` removeKey).
     */
    fun <V> swapRemove(map: LinkedHashMap<String, V>, key: String): V? {
        if (!map.containsKey(key)) return null
        val value = map[key]!!
        val lastKey = map.keys.last()
        if (lastKey == key) {
            map.remove(key)
            return value
        }
        val rebuilt = LinkedHashMap<String, V>()
        for ((name, item) in map) {
            when {
                name == key -> rebuilt[lastKey] = map[lastKey]!!
                name == lastKey -> Unit
                else -> rebuilt[name] = item
            }
        }
        map.clear()
        map.putAll(rebuilt)
        return value
    }
}

/**
 * Ordered item/part mapping for the Responses request body
 * (`providers/openai-responses.ts` convert_to_responses_format_for_endpoint
 * helpers push_message_parts / assistant_tool_call_items / tool_result_item /
 * convert_tool_definition / convert_tool_choice / convert_response_format and
 * sanitize_openai_request_body).
 *
 * Roles and parts keep wire order; raw scoped items (reasoning, web search)
 * pass through untouched; tool declarations are flattened and tool results are
 * joined with newline separators exactly as the frozen desktop does.
 */
internal object ResponsesRequestItems {
    /** Chat-style body messages encoded from the adapter request. */
    fun encodeChatMessages(messages: List<ProviderMessage>): JsonValue.Array = JsonValue.array(messages.map(::encodeChatMessage))

    private fun encodeChatMessage(message: ProviderMessage): JsonValue.Object {
        val entries = LinkedHashMap<String, JsonValue>()
        entries["role"] = JsonValue.text(message.role.wireName)
        chatContent(message)?.let { entries["content"] = it }
        message.toolCallId?.let { entries["tool_call_id"] = JsonValue.text(it) }
        parsedArray(message.toolCallsJson)?.let { entries["tool_calls"] = JsonValue.array(it) }
        message.reasoningContent?.let { entries["reasoning_content"] = JsonValue.text(it) }
        message.responses?.reasoningItemJson?.let { raw -> parsed(raw)?.let { entries["response_reasoning_item"] = it } }
        message.responses?.webSearchItemsJson?.let { raw ->
            parsedArray(raw)?.let {
                entries["response_web_search_items"] =
                    JsonValue.array(it)
            }
        }
        return JsonValue.Object.from(entries)
    }

    /** Desktop message content is a string or typed parts, never both. */
    private fun chatContent(message: ProviderMessage): JsonValue? = when {
        message.text.isNotEmpty() -> JsonValue.text(message.text)
        else -> message.responses?.contentPartsJson?.let(::parsedArray)?.let(JsonValue::array)
    }

    private fun parsed(raw: String): JsonValue? = runCatching { JsonValue.parse(raw) }.getOrNull()

    private fun parsedArray(raw: String?): List<JsonValue>? = raw?.let { parsed(it) as? JsonValue.Array }?.values

    /** providers/mod.rs:914 `push_message_parts`. */
    fun pushMessageParts(parts: MutableList<JsonValue>, role: String, content: JsonValue) {
        if (content is JsonValue.Text) {
            if (content.value.trim().isNotEmpty()) parts += textItem(role, content.value)
            return
        }
        val items = ResponsesJson.array(content) ?: return
        for (part in items) {
            appendPart(parts, role, part, ResponsesJson.text(ResponsesJson.field(part, "type")).orEmpty())
        }
    }

    private fun appendPart(parts: MutableList<JsonValue>, role: String, part: JsonValue, type: String): Boolean = when (type) {
        "input_file", "refusal" -> appendRolePart(parts, role, part, type)

        "input_text" -> if (role == "assistant") appendOutputTextPart(parts, role, part) else appendInputTextPart(parts, part)

        "image_url", "input_image" -> if (role == "assistant") {
            appendOutputTextPart(parts, role, part)
        } else {
            appendImagePart(parts, part, type)
        }

        else -> appendOutputTextPart(parts, role, part)
    }

    private fun appendRolePart(parts: MutableList<JsonValue>, role: String, part: JsonValue, type: String): Boolean = when {
        type == "input_file" && role == "user" -> appendFilePart(parts, part)
        type == "refusal" && role == "assistant" -> appendRefusalPart(parts, part)
        else -> appendOutputTextPart(parts, role, part)
    }

    private fun appendInputTextPart(parts: MutableList<JsonValue>, part: JsonValue): Boolean {
        val text = ResponsesJson.text(ResponsesJson.field(part, "text")) ?: return false
        if (text.isEmpty()) return false
        parts += JsonValue.obj("type" to JsonValue.text("input_text"), "text" to JsonValue.text(text))
        return true
    }

    private fun appendOutputTextPart(parts: MutableList<JsonValue>, role: String, part: JsonValue): Boolean {
        val text = ResponsesJson.text(ResponsesJson.field(part, "text")) ?: return false
        if (text.isEmpty()) return false
        parts += textItem(role, text)
        return true
    }

    private fun appendImagePart(parts: MutableList<JsonValue>, part: JsonValue, type: String): Boolean {
        val imageUrl = if (type == "input_image") {
            ResponsesJson.text(ResponsesJson.field(part, "image_url"))
        } else {
            ResponsesJson.text(ResponsesJson.field(ResponsesJson.field(part, "image_url"), "url"))
        } ?: return false
        val detail = ResponsesJson.field(part, "detail")
        val item = JsonValue.Object.Builder()
            .put("type", "input_image")
            .put("image_url", imageUrl)
            .putIfNotNull("detail", detail?.takeIf { it != JsonValue.Null })
        parts += item.build()
        return true
    }

    private fun appendFilePart(parts: MutableList<JsonValue>, part: JsonValue): Boolean {
        val fileId = ResponsesJson.text(ResponsesJson.field(part, "file_id")) ?: return false
        if (fileId.isEmpty()) return false
        parts += JsonValue.obj("type" to JsonValue.text("input_file"), "file_id" to JsonValue.text(fileId))
        return true
    }

    private fun appendRefusalPart(parts: MutableList<JsonValue>, part: JsonValue): Boolean {
        val refusal = ResponsesJson.text(ResponsesJson.field(part, "refusal")) ?: return false
        if (refusal.isEmpty()) return false
        parts += JsonValue.obj("type" to JsonValue.text("refusal"), "refusal" to JsonValue.text(refusal))
        return true
    }

    private fun textItem(role: String, text: String): JsonValue.Object = JsonValue.obj(
        "type" to JsonValue.text(if (role == "assistant") "output_text" else "input_text"),
        "text" to JsonValue.text(text),
    )

    /** providers/mod.rs:995 `assistant_tool_call_items`. */
    fun assistantToolCallItems(message: JsonValue): List<JsonValue> {
        val calls = ResponsesJson.array(ResponsesJson.field(message, "tool_calls")) ?: return emptyList()
        return calls.map { toolCall ->
            val id = ResponsesJson.text(ResponsesJson.field(toolCall, "id")) ?: "call_unknown"
            val fn = ResponsesJson.obj(ResponsesJson.field(toolCall, "function"))
            val name = fn?.get("name")?.let(ResponsesJson::text) ?: "unknown_function"
            val arguments = fn?.get("arguments")?.let(ResponsesJson::text) ?: "{}"
            JsonValue.obj(
                "type" to JsonValue.text("function_call"),
                "call_id" to JsonValue.text(id),
                "name" to JsonValue.text(name),
                "arguments" to JsonValue.text(arguments),
            )
        }
    }

    /** providers/mod.rs:1024 `tool_result_item`. */
    fun toolResultItem(message: JsonValue): JsonValue? {
        val callId = ResponsesJson.text(ResponsesJson.field(message, "tool_call_id")) ?: return null
        val content = ResponsesJson.field(message, "content")
        val output = when (content) {
            is JsonValue.Text -> content.value

            is JsonValue.Array -> content.values.mapNotNull { ResponsesJson.text(ResponsesJson.field(it, "text")) }
                .joinToString("\n")

            else -> ""
        }
        return JsonValue.obj(
            "type" to JsonValue.text("function_call_output"),
            "call_id" to JsonValue.text(callId),
            "output" to JsonValue.text(output),
        )
    }

    /** providers/mod.rs:1074 `convert_tool_definition_to_responses`. */
    fun convertToolDefinition(tool: JsonValue): JsonValue {
        val type = ResponsesJson.text(ResponsesJson.field(tool, "type")) ?: "function"
        if (type != "function") return tool
        val fn = ResponsesJson.obj(ResponsesJson.field(tool, "function")) ?: return tool
        val flat = LinkedHashMap<String, JsonValue>()
        flat["type"] = JsonValue.text("function")
        for (key in listOf("name", "description", "parameters")) {
            ResponsesJson.field(fn, key)?.let { flat[key] = it }
        }
        flat["strict"] = ResponsesJson.field(fn, "strict") ?: JsonValue.bool(false)
        return JsonValue.Object.from(flat)
    }

    /** providers/mod.rs:1043 `convert_response_tool_choice`. */
    fun convertToolChoice(value: JsonValue): JsonValue? = when (value) {
        is JsonValue.Text -> if (value.value in TOOL_CHOICE_STRINGS) value else null

        else -> {
            val type = ResponsesJson.text(ResponsesJson.field(value, "type")).orEmpty()
            if (type != "function" && type != "tool") {
                null
            } else {
                val name = ResponsesJson.text(ResponsesJson.field(value, "name"))
                    ?: ResponsesJson.text(ResponsesJson.field(ResponsesJson.field(value, "function"), "name"))
                name?.let { JsonValue.obj("type" to JsonValue.text("function"), "name" to JsonValue.text(it)) }
            }
        }
    }

    /** providers/mod.rs:1108 `convert_response_format_to_text_format`. */
    fun convertResponseFormat(responseFormat: JsonValue): JsonValue {
        if (ResponsesJson.text(ResponsesJson.field(responseFormat, "type")) != "json_schema") return responseFormat
        val nested = ResponsesJson.obj(ResponsesJson.field(responseFormat, "json_schema")) ?: return responseFormat
        val flat = LinkedHashMap<String, JsonValue>()
        flat["type"] = JsonValue.text("json_schema")
        for ((key, value) in nested.asMap()) if (key != "type") flat[key] = value
        return JsonValue.Object.from(flat)
    }

    /** providers/openai-chat.ts sanitizeOpenaiRequestBody for the Responses source body. */
    fun sanitizeOpenaiRequestBody(body: JsonValue.Object): JsonValue.Object {
        val entries = LinkedHashMap(body.asMap())
        val tools = entries["tools"] as? JsonValue.Array
        val validNames = if (tools == null) emptySet() else cleanTools(entries, tools)
        return sanitizeToolChoice(entries, validNames)
    }

    /** Returns the valid function names of the surviving tools. */
    private fun cleanTools(entries: LinkedHashMap<String, JsonValue>, tools: JsonValue.Array): Set<String> {
        val cleaned = mutableListOf<JsonValue>()
        val seenNames = mutableSetOf<String>()
        for (tool in tools.values) cleanTool(tool, seenNames)?.let(cleaned::add)
        if (cleaned.isEmpty()) {
            ResponsesJson.swapRemove(entries, "tools")
            ResponsesJson.swapRemove(entries, "tool_choice")
            return emptySet()
        }
        entries["tools"] = JsonValue.array(cleaned)
        return validFunctionNames(cleaned)
    }

    private fun cleanTool(tool: JsonValue, seenNames: MutableSet<String>): JsonValue? {
        if (declaresBlankName(tool)) return null
        if ((ResponsesJson.text(ResponsesJson.field(tool, "type")) ?: "function") != "function") return tool
        val fn = ResponsesJson.obj(ResponsesJson.field(tool, "function")) ?: return null
        val name = ResponsesJson.text(fn["name"]).orEmpty().trim()
        if (name.isEmpty() || !seenNames.add(name)) return null
        val rebuilt = LinkedHashMap(ResponsesJson.obj(tool)?.asMap() ?: emptyMap())
        rebuilt["function"] = cleanedFunction(fn, name)
        return JsonValue.Object.from(rebuilt)
    }

    private fun validFunctionNames(cleaned: List<JsonValue>): Set<String> = cleaned
        .mapNotNull { ResponsesJson.text(ResponsesJson.field(ResponsesJson.field(it, "function"), "name")) }
        .toSet()

    private fun declaresBlankName(tool: JsonValue): Boolean {
        val flat = ResponsesJson.text(ResponsesJson.field(tool, "name"))
        val nested = ResponsesJson.text(ResponsesJson.field(ResponsesJson.field(tool, "function"), "name"))
        return (flat != null && flat.trim().isEmpty()) || (nested != null && nested.trim().isEmpty())
    }

    private fun cleanedFunction(fn: JsonValue.Object, name: String): JsonValue.Object {
        val entries = LinkedHashMap(fn.asMap())
        entries["name"] = JsonValue.text(name)
        val parameters = entries["parameters"] as? JsonValue.Object
        if (parameters == null) {
            entries["parameters"] = JsonValue.obj("type" to JsonValue.text("object"), "properties" to JsonValue.Object.EMPTY)
        } else {
            val updated = LinkedHashMap(parameters.asMap())
            updated.putIfAbsent("type", JsonValue.text("object"))
            updated.putIfAbsent("properties", JsonValue.Object.EMPTY)
            entries["parameters"] = JsonValue.Object.from(updated)
        }
        return JsonValue.Object.from(entries)
    }

    private fun sanitizeToolChoice(entries: LinkedHashMap<String, JsonValue>, validNames: Set<String>): JsonValue.Object {
        val choice = entries["tool_choice"] ?: return JsonValue.Object.from(entries)
        when {
            choice is JsonValue.Text && choice.value !in TOOL_CHOICE_STRINGS -> ResponsesJson.swapRemove(entries, "tool_choice")
            choice is JsonValue.Text -> Unit
            isWritableFunctionChoice(choice, validNames) -> entries["tool_choice"] = functionChoice(choice)
            else -> ResponsesJson.swapRemove(entries, "tool_choice")
        }
        return JsonValue.Object.from(entries)
    }

    private fun isWritableFunctionChoice(choice: JsonValue, validNames: Set<String>): Boolean {
        val type = ResponsesJson.text(ResponsesJson.field(choice, "type")).orEmpty()
        if (type != "function" && type != "tool") return false
        return toolChoiceName(choice) in validNames
    }

    private fun functionChoice(choice: JsonValue): JsonValue.Object = JsonValue.obj(
        "type" to JsonValue.text("function"),
        "function" to JsonValue.obj("name" to JsonValue.text(toolChoiceName(choice))),
    )

    private fun toolChoiceName(choice: JsonValue): String = (
        ResponsesJson.text(ResponsesJson.field(choice, "name"))
            ?: ResponsesJson.text(ResponsesJson.field(ResponsesJson.field(choice, "function"), "name"))
        )?.trim().orEmpty()

    private val TOOL_CHOICE_STRINGS = listOf("auto", "none", "required")
}

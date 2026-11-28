package com.deepstudent.services.providers.data

import com.deepstudent.core.common.json.JsonValue

/**
 * Port of the frozen OpenAI-to-Gemini draft conversion
 * (host/plugins/llm-providers/src/adapters/gemini-openai-converter.ts,
 * `convert_openai_to_gemini` and `generation_config_from_request`).
 */
internal fun convertOpenAiToGemini(request: OpenAiShape.Shape, stats: GoogleGeminiConverter.Stats): GoogleGeminiConverter.Draft {
    val draft = GoogleGeminiConverter.Draft()
    collectContents(request, draft, stats)
    mergeConsecutiveTurns(draft, stats)
    downgradeUnprotectedToolCalls(draft, stats)
    insertLeadingUserTurn(draft, stats)
    if (draft.contents.isEmpty() && draft.systemInstruction == null) {
        throw GoogleGeminiConverter.buildFailure("invalid_format", "No valid content to convert")
    }
    draft.generationConfig = generationConfig(request.generation)
    applyTools(draft, request)
    request.toolChoice?.let { draft.toolConfig = toolChoiceConfig(it) }
    return draft
}

private fun collectContents(request: OpenAiShape.Shape, draft: GoogleGeminiConverter.Draft, stats: GoogleGeminiConverter.Stats) {
    for (message in request.messages) {
        when (message.role) {
            "system" -> appendSystemInstruction(message, draft)
            "user" -> appendUserContent(message, draft, stats)
            "assistant" -> appendAssistantContent(message, draft)
            "function", "tool" -> appendToolResult(message, request, draft)
        }
    }
}

/** The last non-empty system message wins, as in the frozen loop. */
private fun appendSystemInstruction(message: OpenAiShape.Message, draft: GoogleGeminiConverter.Draft) {
    val text = extractText(message.content ?: return)
    if (text.isEmpty()) return
    draft.systemInstruction = GoogleGeminiConverter.Content("user", mutableListOf(JsonValue.obj("text" to JsonValue.text(text))))
}

private fun appendUserContent(message: OpenAiShape.Message, draft: GoogleGeminiConverter.Draft, stats: GoogleGeminiConverter.Stats) {
    val content = message.content ?: return
    val parts = convertContentToParts(content, stats)
    if (parts.isNotEmpty()) draft.contents += GoogleGeminiConverter.Content("user", parts)
}

private fun appendAssistantContent(message: OpenAiShape.Message, draft: GoogleGeminiConverter.Draft) {
    val parts = mutableListOf<JsonValue>()
    message.content?.let { content ->
        val text = extractText(content)
        if (text.isNotEmpty()) parts += JsonValue.obj("text" to JsonValue.text(text))
    }
    message.toolCalls.orEmpty().forEachIndexed { index, call ->
        if (call.type != "function") return@forEachIndexed
        val args = runCatching { JsonValue.parse(call.arguments) }.getOrElse { JsonValue.Object.EMPTY }
        val functionCall = LinkedHashMap<String, JsonValue>()
        functionCall["name"] = JsonValue.text(call.name)
        functionCall["args"] = args
        nativeToolCallId(call.id)?.let { functionCall["id"] = JsonValue.text(it) }
        val part = LinkedHashMap<String, JsonValue>()
        part["functionCall"] = JsonValue.Object.from(functionCall)
        // Gemini 3 requires the signature on the first functionCall part only.
        if (index == 0) message.thoughtSignature?.let { part["thoughtSignature"] = JsonValue.text(it) }
        parts += JsonValue.Object.from(part)
    }
    if (parts.isNotEmpty()) draft.contents += GoogleGeminiConverter.Content("model", parts)
}

private fun appendToolResult(message: OpenAiShape.Message, request: OpenAiShape.Shape, draft: GoogleGeminiConverter.Draft) {
    val resolvedName = message.name ?: previousToolName(request.messages, message.toolCallId) ?: return
    val content = message.content ?: return
    val responseText = extractText(content)
    val response = runCatching { JsonValue.parse(responseText) }.getOrElse {
        JsonValue.obj("result" to JsonValue.text(responseText))
    }
    val functionResponse = LinkedHashMap<String, JsonValue>()
    functionResponse["name"] = JsonValue.text(resolvedName)
    functionResponse["response"] = response
    message.toolCallId?.let { id -> nativeToolCallId(id)?.let { functionResponse["id"] = JsonValue.text(it) } }
    val part = JsonValue.obj("functionResponse" to JsonValue.Object.from(functionResponse))
    val last = draft.contents.lastOrNull()
    if (last != null && last.role == "user" && last.parts.all { it.objectFields().containsKey("functionResponse") }) {
        last.parts += part
    } else {
        draft.contents += GoogleGeminiConverter.Content("user", mutableListOf(part))
    }
}

private fun previousToolName(messages: List<OpenAiShape.Message>, id: String?): String? {
    if (id == null) return null
    return messages.asSequence().filter { it.role == "assistant" }.flatMap { it.toolCalls.orEmpty().asSequence() }
        .firstOrNull { it.id == id }?.name
}

private fun mergeConsecutiveTurns(draft: GoogleGeminiConverter.Draft, stats: GoogleGeminiConverter.Stats) {
    if (draft.contents.size < 2) return
    val merged = mutableListOf<GoogleGeminiConverter.Content>()
    for (content in draft.contents) {
        val last = merged.lastOrNull()
        if (last != null && last.role == content.role) {
            last.parts.addAll(content.parts)
            stats.mergedTurns += 1
        } else {
            merged += GoogleGeminiConverter.Content(content.role, content.parts)
        }
    }
    draft.contents.clear()
    draft.contents.addAll(merged)
}

/**
 * Gemini 3 rejects functionCall parts without a real thoughtSignature: they
 * degrade to text together with the following tool results.
 */
private fun downgradeUnprotectedToolCalls(draft: GoogleGeminiConverter.Draft, stats: GoogleGeminiConverter.Stats) {
    for (index in draft.contents.indices) {
        val content = draft.contents[index]
        if (content.role != "model") continue
        val count = downgradeFunctionCalls(content)
        if (count == 0) continue
        stats.downgradedToolParts += count
        draft.contents.getOrNull(index + 1)?.takeIf { it.role == "user" }?.let(::downgradeFunctionResponses)
    }
}

private fun downgradeFunctionCalls(content: GoogleGeminiConverter.Content): Int {
    var downgraded = 0
    for (index in content.parts.indices) {
        val part = content.parts[index].objectFields()
        if (part.containsKey("thoughtSignature")) continue
        val call = part["functionCall"] as? JsonValue.Object ?: continue
        val name = jsonText(call, "name").orEmpty()
        val args = call["args"] ?: JsonValue.Object.EMPTY
        content.parts[index] = JsonValue.obj("text" to JsonValue.text("[Tool call: $name(${args.literal()})]"))
        downgraded += 1
    }
    return downgraded
}

private fun downgradeFunctionResponses(content: GoogleGeminiConverter.Content) {
    for (partIndex in content.parts.indices) {
        val response = content.parts[partIndex].objectFields()["functionResponse"] as? JsonValue.Object ?: continue
        val name = (response.asMap().get("name") as? JsonValue.Text)?.value ?: ""
        val value = response.asMap().get("response") ?: JsonValue.Object.EMPTY
        content.parts[partIndex] = JsonValue.obj("text" to JsonValue.text("[Tool result for $name: ${value.literal()}]"))
    }
}

private fun insertLeadingUserTurn(draft: GoogleGeminiConverter.Draft, stats: GoogleGeminiConverter.Stats) {
    if (draft.contents.isNotEmpty() && draft.contents.first().role == "model") {
        draft.contents.add(0, GoogleGeminiConverter.Content("user", mutableListOf(JsonValue.obj("text" to JsonValue.text(".")))))
        stats.insertedUserTurn = true
    }
}

private fun extractText(content: OpenAiShape.Content): String = when (content) {
    is OpenAiShape.Content.Text -> content.text
    is OpenAiShape.Content.Parts -> content.parts.mapNotNull { (it as? OpenAiShape.Part.Text)?.text }.joinToString("\n")
}

private fun convertContentToParts(content: OpenAiShape.Content, stats: GoogleGeminiConverter.Stats): MutableList<JsonValue> {
    val parts = mutableListOf<JsonValue>()
    val items: List<OpenAiShape.Part> = when (content) {
        is OpenAiShape.Content.Text -> listOf(OpenAiShape.Part.Text(content.text))
        is OpenAiShape.Content.Parts -> content.parts
    }
    for (part in items) {
        when (part) {
            is OpenAiShape.Part.Text -> if (part.text.isNotEmpty()) parts += JsonValue.obj("text" to JsonValue.text(part.text))
            is OpenAiShape.Part.Image -> imageData(part.url)?.let { parts += JsonValue.obj("inlineData" to it) }
            OpenAiShape.Part.Unsupported -> stats.unsupportedParts += 1
        }
    }
    return parts
}

/** Frozen `image_url_to_inline_data`: data URLs inline; anything else has no injected bytes here. */
private fun imageData(url: String): JsonValue.Object? {
    if (!url.startsWith("data:")) return null
    val comma = url.indexOf(',')
    if (comma < 0) return null
    val header = url.substring(0, comma)
    val data = url.substring(comma + 1)
    val mimeType = header.replace(Regex("^(?:data:)+"), "").replace(Regex("(?:;base64)+$"), "")
    return JsonValue.obj("mimeType" to JsonValue.text(mimeType), "data" to JsonValue.text(data))
}

private fun generationConfig(request: OpenAiShape.Generation): JsonValue.Object? {
    val fields = LinkedHashMap<String, JsonValue>()
    request.temperature?.let { fields["temperature"] = geminiF32ShortestNumber(it) }
    request.topP?.let { fields["topP"] = geminiF32ShortestNumber(it) }
    request.frequencyPenalty?.let { fields["frequencyPenalty"] = geminiF32ShortestNumber(it) }
    request.presencePenalty?.let { fields["presencePenalty"] = geminiF32ShortestNumber(it) }
    request.maxTokens?.let { fields["maxOutputTokens"] = JsonValue.Number.integer(it) }
    request.stop?.let { fields["stopSequences"] = JsonValue.array(it.map(JsonValue::text)) }
    applyResponseFormat(fields, request.responseFormat as? JsonValue.Object)
    return if (fields.isEmpty()) null else JsonValue.Object.from(fields)
}

private fun applyResponseFormat(fields: MutableMap<String, JsonValue>, format: JsonValue.Object?) {
    val type = jsonText(format, "type")
    if (type != "json_object" && type != "json_schema") return
    fields["responseMimeType"] = JsonValue.text("application/json")
    if (type == "json_schema") format?.get("json_schema")?.let { fields["responseSchema"] = responseSchema(it) }
}

private fun responseSchema(holder: JsonValue): JsonValue {
    val objectHolder = holder as? JsonValue.Object ?: return holder
    return objectHolder["schema"] ?: holder
}

private fun applyTools(draft: GoogleGeminiConverter.Draft, request: OpenAiShape.Shape) {
    val tools = request.tools ?: return
    val declarations = tools.filter { it.type == "function" }.map { tool ->
        JsonValue.obj(
            "name" to JsonValue.text(tool.name),
            "description" to JsonValue.text(tool.description ?: ""),
            "parameters" to fixSchemaForGemini(tool.parameters),
        )
    }
    if (declarations.isNotEmpty()) {
        draft.tools =
            JsonValue.array(listOf(JsonValue.obj("functionDeclarations" to JsonValue.array(declarations))))
    }
}

private fun toolChoiceConfig(choice: JsonValue): JsonValue.Object? = when {
    choice is JsonValue.Text && choice.value == "auto" -> mode("AUTO")
    choice is JsonValue.Text && choice.value == "none" -> mode("NONE")
    choice is JsonValue.Object -> namedFunction(choice) ?: directFunction(choice)
    else -> null
}

private fun namedFunction(choice: JsonValue.Object): JsonValue.Object? {
    if ((choice["type"] as? JsonValue.Text)?.value != "function") return null
    val name = (jsonObject(choice, "function")?.get("name") as? JsonValue.Text)?.value ?: return null
    return allowedNames(name)
}

private fun directFunction(choice: JsonValue.Object): JsonValue.Object? {
    val name = (choice["function"] as? JsonValue.Text)?.value ?: return null
    return allowedNames(name)
}

private fun allowedNames(vararg names: String): JsonValue.Object = JsonValue.obj(
    "functionCallingConfig" to JsonValue.obj(
        "mode" to JsonValue.text("ANY"),
        "allowedFunctionNames" to JsonValue.array(names.map(JsonValue::text)),
    ),
)

private fun mode(value: String): JsonValue.Object = JsonValue.obj("functionCallingConfig" to JsonValue.obj("mode" to JsonValue.text(value)))

/** Frozen `fix_schema_for_gemini`: array items, item types and enum values. */
internal fun fixSchemaForGemini(value: JsonValue): JsonValue = when (value) {
    is JsonValue.Array -> JsonValue.array(value.values.map(::fixSchemaForGemini))
    is JsonValue.Object -> fixObjectSchema(value)
    else -> value
}

private fun fixObjectSchema(value: JsonValue.Object): JsonValue.Object {
    val fields = LinkedHashMap(value.asMap())
    fixSchemaItems(fields)
    (fields["enum"] as? JsonValue.Array)?.let { enumValues ->
        fields["enum"] = JsonValue.array(enumValues.values.map(::enumText))
        fields["type"] = JsonValue.text("string")
    }
    return JsonValue.Object.from(fields.mapValues { (_, item) -> fixSchemaForGemini(item) })
}

private fun fixSchemaItems(fields: MutableMap<String, JsonValue>) {
    if (fields["type"] == JsonValue.text("array") && !fields.containsKey("items")) {
        fields["items"] = JsonValue.obj("type" to JsonValue.text("string"))
    }
    val items = fields["items"] as? JsonValue.Object ?: return
    if (items.containsKey("type")) return
    val itemType = if (items.containsKey("properties")) "object" else "string"
    fields["items"] = JsonValue.Object.Builder().putAll(items).put("type", itemType).build()
}

private fun enumText(value: JsonValue): JsonValue = when (value) {
    is JsonValue.Number -> JsonValue.text(value.literal)
    is JsonValue.Bool -> JsonValue.text(if (value.value) "true" else "false")
    else -> value
}

private fun jsonText(value: JsonValue?, key: String): String? {
    val holder = value as? JsonValue.Object ?: return null
    return (holder[key] as? JsonValue.Text)?.value
}

private fun jsonObject(value: JsonValue?, key: String): JsonValue.Object? {
    val holder = value as? JsonValue.Object ?: return null
    return holder[key] as? JsonValue.Object
}

private fun JsonValue.objectFields(): Map<String, JsonValue> = (this as? JsonValue.Object)?.asMap() ?: emptyMap()

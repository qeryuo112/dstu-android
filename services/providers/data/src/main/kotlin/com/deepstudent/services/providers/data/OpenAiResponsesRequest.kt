package com.deepstudent.services.providers.data

import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.services.providers.api.AuthMode
import com.deepstudent.services.providers.api.ProviderAdapterRequest
import com.deepstudent.services.providers.api.ProviderTool
import java.net.URI
import java.util.Locale

/**
 * Pure encoder for the OpenAI Responses wire body
 * (`host/plugins/llm-providers/src/providers/openai-responses.ts` buildRequest
 * and `llm/src/{question_authoring,generation,provider_quirks,passback,
 * prepared_request,prompt_cache}.ts` at e6b2657fd59014a73f5bbb1d14c2db732cf23e87).
 *
 * Pipeline: chat-style body, adapter reasoning rules, tools, generation
 * params, typed extra options, official DeepSeek passback healing, then the
 * frozen Responses conversion (ordered role/item/part mapping) and finally the
 * prompt-cache key or the Codex restricted body. Headers and the endpoint are
 * owned by the exchange and route layers.
 */
internal object OpenAiResponsesRequest {
    fun build(request: ProviderAdapterRequest): ResponsesWireBody {
        val state = ResponsesRequestState(request)
        val body = chatBody(state)
        ResponsesRequestRules.applyReasoning(body, state)
        applyTools(body, state)
        ResponsesRequestRules.applyGeneration(body, state)
        applyOptions(body, state)
        DeepseekResponsesRules.healOfficialPassback(body, state)
        val payload = ResponsesRequestPayload.convert(state.model, body.build(), state.baseUrl)
        val moderated = moderationFlags(state, payload)
        return ResponsesWireBody(finalizeBody(state, payload), moderated.first, moderated.second)
    }

    private fun chatBody(state: ResponsesRequestState): RequestBody = RequestBody(
        linkedMapOf(
            "model" to JsonValue.text(state.model),
            "messages" to ResponsesRequestItems.encodeChatMessages(state.request.messages),
            "stream" to JsonValue.bool(true),
        ),
    )

    /**
     * Chat-style declarations; raw extra tools ride in extraBody and pass
     * through unconverted. tool_choice rides only with tools
     * (llm/src/model2_request.ts:79,139-142).
     */
    private fun applyTools(body: RequestBody, state: ResponsesRequestState) {
        val tools = state.request.tools.map(::chatTool) + extraRawTools(state)
        if (tools.isEmpty()) return
        body.put("tools", JsonValue.array(tools))
        body.put("tool_choice", "auto")
    }

    private fun chatTool(tool: ProviderTool): JsonValue.Object {
        val function = JsonValue.Object.Builder()
            .put("name", tool.name)
            .put("description", tool.description)
            .put("parameters", toolParameters(tool))
            .putIfNotNull("strict", tool.strict?.let(JsonValue::bool))
        return JsonValue.obj("type" to JsonValue.text("function"), "function" to function.build())
    }

    private fun toolParameters(tool: ProviderTool): JsonValue.Object = runCatching {
        JsonValue.parseObject(tool.inputSchemaJson)
    }.getOrElse { JsonValue.Object.EMPTY }

    private fun extraRawTools(state: ResponsesRequestState): List<JsonValue> {
        val raw = state.parameters.typedExtraBody["tools"] ?: state.parameters.extraBody["tools"] ?: return emptyList()
        return (runCatching { JsonValue.parse(raw) }.getOrNull() as? JsonValue.Array)?.values ?: emptyList()
    }

    /**
     * Frozen `Object.assign(body, options)` for the already-resolved request
     * knobs: every extra key overrides, except the request identity fields and
     * the tool declarations consumed by [applyTools].
     */
    private fun applyOptions(body: RequestBody, state: ResponsesRequestState) {
        val merged = LinkedHashMap<String, String>()
        state.parameters.extraBody.forEach { (key, value) -> merged[key] = value }
        state.parameters.typedExtraBody.forEach { (key, value) -> merged[key] = value }
        for ((key, raw) in merged) {
            if (key.isBlank() || key in RESERVED_KEYS) continue
            body.put(key, optionValue(raw))
        }
    }

    /**
     * Typed extras carry JSON literals (a JSON string keeps its exact text);
     * legacy extras carry raw strings that stay text when not valid JSON.
     */
    private fun optionValue(raw: String): JsonValue = runCatching { JsonValue.parse(raw) }.getOrElse { JsonValue.text(raw) }

    private fun finalizeBody(state: ResponsesRequestState, payload: JsonValue.Object): JsonValue.Object {
        if (state.resolved.authMode == AuthMode.OPENAI_CODEX_OAUTH) {
            return ResponsesRequestPayload.prepareCodexResponsesBody(payload, codexSessionId(state.sessionId))
        }
        if (!acceptsPromptCacheKey(state)) return payload
        val entries = LinkedHashMap(payload.asMap())
        entries.putIfAbsent("prompt_cache_key", JsonValue.text(codexSessionId(state.sessionId)))
        return JsonValue.Object.from(entries)
    }

    /** OpenAI cache affinity is written on Responses routes except official DeepSeek. */
    private fun acceptsPromptCacheKey(state: ResponsesRequestState): Boolean = !DeepseekResponsesRules.isOfficialDeepseekConfig(state)

    /** providers/openai-responses.ts buildRequest moderation flags for the official flash route. */
    private fun moderationFlags(state: ResponsesRequestState, payload: JsonValue.Object): Pair<Boolean, Boolean> {
        val deepseekFlash = urlOrigin(state.baseUrl) == "https://api.deepseek.com" && state.model == "deepseek-flash"
        if (!deepseekFlash) return false to false
        val toolTypes = (payload["tools"] as? JsonValue.Array)?.values
            ?.mapNotNull { ResponsesJson.text(ResponsesJson.field(it, "type")) }
            .orEmpty()
        val webTools = toolTypes.any { it in DEEPSEEK_WEB_TOOLS }
        val moderated = webTools || payload["safety_check"]?.let { ResponsesJson.field(it, "level") } == JsonValue.text("high")
        return moderated to webTools
    }

    /** WHATWG URL origin (scheme, lowercased host, non-default port). */
    private fun urlOrigin(value: String): String? = runCatching {
        val url = URI(value.trim())
        val scheme = url.scheme?.lowercase(Locale.ROOT) ?: return null
        val host = url.host?.lowercase(Locale.ROOT) ?: return null
        val defaultPort = when (scheme) {
            "https" -> 443
            "http" -> 80
            else -> -1
        }
        val port = url.port
        "$scheme://$host${if (port == -1 || port == defaultPort) "" else ":$port"}"
    }.getOrNull()

    private val RESERVED_KEYS = setOf("model", "messages", "stream", "tools")

    private val DEEPSEEK_WEB_TOOLS = setOf("deepseek_web_search", "deepseek_read_url")
}

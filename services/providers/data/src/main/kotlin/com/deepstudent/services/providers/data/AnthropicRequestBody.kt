package com.deepstudent.services.providers.data

import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.services.providers.api.ProviderAdapterRequest

/**
 * Encoded Anthropic POST payload: the converted wire body, its resolved
 * same-origin `/v1/messages` URL, and the beta feature list frozen at
 * buildRequest time (`providers/anthropic.ts` buildRequest at
 * e6b2657fd59014a73f5bbb1d14c2db732cf23e87). Headers live with the exchange
 * because the API key never enters this value.
 */
internal class AnthropicWireBody(val json: JsonValue.Object, val url: String, val betaFeatures: List<String>)

internal object AnthropicRequestBody {
    /**
     * Frozen pipeline order: OpenAI-style intermediate body, reasoning rules,
     * tool declarations, generation params, configured options, then the wire
     * conversion. `targetScope` binds the sidecar replay blocks to the route.
     */
    fun build(request: ProviderAdapterRequest, sidecar: AnthropicRequestSidecar?, targetScope: String): AnthropicWireBody {
        val state = AnthropicRequestState(request)
        val body = intermediateBody(request, sidecar, targetScope, state)
        AnthropicRequestRules.applyReasoning(body, state)
        AnthropicRequestRules.applyTools(body, state)
        AnthropicRequestRules.applyGeneration(body, state)
        AnthropicRequestRules.applyOptions(body, state)
        val intermediate = body.build()
        val json = AnthropicWireConvert.convert(state.model, intermediate, sidecar?.imageSource)
        return AnthropicWireBody(json, AnthropicEndpoint.messagesUrl(state.baseUrl), betaFeatures(state.model, intermediate))
    }

    private fun intermediateBody(
        request: ProviderAdapterRequest,
        sidecar: AnthropicRequestSidecar?,
        targetScope: String,
        state: AnthropicRequestState,
    ): RequestBody = RequestBody(
        linkedMapOf(
            "model" to JsonValue.text(state.model),
            "messages" to encodeAnthropicChatMessages(request, sidecar, targetScope),
            "stream" to JsonValue.bool(true),
        ),
    )

    /**
     * Frozen beta headers: interleaved thinking for manual-generation Claude 4
     * models with tools and enabled budget thinking, and the effort beta for a
     * configured effort on non-adaptive generations.
     */
    private fun betaFeatures(model: String, intermediate: JsonValue.Object): List<String> =
        listOfNotNull(interleavedBeta(model, intermediate), effortBeta(model, intermediate))

    /** Manual-generation Claude 4 models with tools and budget thinking need interleaved thinking. */
    private fun interleavedBeta(model: String, intermediate: JsonValue.Object): String? {
        val thinkingEnabled = ResponsesJson.text(ResponsesJson.field(intermediate["thinking"], "type")) == "enabled"
        val hasTools = ResponsesJson.array(intermediate["tools"])?.isNotEmpty() == true
        val claude4 = model.contains("claude-4") || model.contains("claude-opus-4") || model.contains("claude-sonnet-4")
        return if (thinkingEnabled && hasTools && claudeGeneration(model) == ClaudeGeneration.MANUAL && claude4) {
            "interleaved-thinking-2025-05-14"
        } else {
            null
        }
    }

    /** A configured effort on a non-adaptive generation needs the effort beta. */
    private fun effortBeta(model: String, intermediate: JsonValue.Object): String? =
        if (intermediate.containsKey("effort") && claudeGeneration(model) != ClaudeGeneration.ADAPTIVE) "effort-2025-11-24" else null
}

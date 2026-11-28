package com.deepstudent.services.providers.data

import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.services.providers.api.ProviderAdapterRequest
import com.deepstudent.services.providers.api.ProviderMessage
import com.deepstudent.services.providers.api.ProviderRole
import java.net.URI

/** Pure request helpers for OpenAI-compatible Chat Completions bodies. */
object OpenAiChatRequest {
    /**
     * Legacy S3.03 shape retained for bespoke direct/custom calls. Provider-aware
     * requests use [body] with a resolved profile instead.
     */
    fun body(model: String, messages: List<ProviderMessage>): JsonValue.Object = body(model, messages, includeUsage = true)

    fun body(model: String, messages: List<ProviderMessage>, includeUsage: Boolean): JsonValue.Object {
        require(model.isNotBlank())
        require(messages.isNotEmpty())
        val builder = JsonValue.Object.Builder()
            .put("model", model)
            .put("messages", encodeMessages(messages, Passback.NONE))
            .put("stream", true)
        if (includeUsage) {
            builder.put("stream_options", JsonValue.obj("include_usage" to JsonValue.bool(true)))
        }
        return builder.build()
    }

    fun body(request: ProviderAdapterRequest, profile: OpenAiChatProviderProfile): JsonValue.Object {
        require(request.messages.isNotEmpty())
        val state = RequestState(request, profile)
        val rules = OpenAiChatRequestRulesResolver.rules(profile)
        val passback = passback(profile, state)
        val body = RequestBody(
            linkedMapOf(
                "model" to JsonValue.text(state.model),
                "messages" to encodeMessages(request.messages, passback),
                "stream" to JsonValue.bool(true),
            ),
        )
        if (rules.applyReasoning(body, state) != EarlyReturn.STOP) rules.applyCommon(body, state)
        state.putTools(body)
        body.putTokens(generationTokenField(state), state.maxTokens)
        if (samplingAllowed(state)) state.putSampling(body)
        addOfficialUsageOption(body, state)
        return body.build()
    }

    private fun generationTokenField(state: RequestState): String = when {
        // Frozen preparePlainProviderRequest normalizes this hosted Chat dialect after generation.
        state.profile == OpenAiChatProviderProfile.SILICONFLOW_DEEPSEEK -> "max_tokens"

        state.isMimo() || (state.isReasoning && !state.isMistral()) -> "max_completion_tokens"

        else -> "max_tokens"
    }

    private fun samplingAllowed(state: RequestState): Boolean = !state.isReasoning || state.isMimo()

    private fun addOfficialUsageOption(body: RequestBody, state: RequestState) {
        if (isOfficialOpenAi(state.baseUrl) && !body.has("stream_options")) {
            body.put("stream_options", JsonValue.obj("include_usage" to JsonValue.bool(true)))
        }
    }

    private fun encodeMessages(messages: List<ProviderMessage>, passback: Passback): JsonValue.Array = JsonValue.array(
        messages.map { message ->
            val body = LinkedHashMap<String, JsonValue>()
            body["role"] = JsonValue.text(role(message.role))
            body["content"] = JsonValue.text(message.text)
            message.toolCallsJson?.let { raw ->
                body["tool_calls"] = runCatching { JsonValue.parse(raw) }.getOrElse { JsonValue.array(emptyList()) }
            }
            message.name?.let { body["name"] = JsonValue.text(it) }
            message.toolCallId?.let { body["tool_call_id"] = JsonValue.text(it) }
            if (message.role == ProviderRole.ASSISTANT && !message.reasoningContent.isNullOrBlank()) {
                when (passback) {
                    Passback.DEEPSEEK -> body["reasoning_content"] = JsonValue.text(checkNotNull(message.reasoningContent))

                    Passback.REASONING_DETAILS -> body["reasoning_details"] = JsonValue.array(
                        listOf(
                            JsonValue.obj(
                                "type" to JsonValue.text("thinking"),
                                "text" to JsonValue.text(checkNotNull(message.reasoningContent)),
                            ),
                        ),
                    )

                    Passback.NONE -> Unit
                }
            }
            JsonValue.Object.from(body)
        },
    )

    private fun passback(profile: OpenAiChatProviderProfile, state: RequestState): Passback = when {
        profile == OpenAiChatProviderProfile.MINIMAX -> Passback.REASONING_DETAILS

        profile == OpenAiChatProviderProfile.DOUBAO || profile == OpenAiChatProviderProfile.MIMO ||
            profile == OpenAiChatProviderProfile.ERNIE -> Passback.DEEPSEEK

        profile == OpenAiChatProviderProfile.QWEN || state.isQwen() -> Passback.DEEPSEEK

        profile == OpenAiChatProviderProfile.SILICONFLOW_DEEPSEEK -> Passback.DEEPSEEK

        else -> Passback.NONE
    }

    private enum class Passback { DEEPSEEK, REASONING_DETAILS, NONE }

    private fun role(role: ProviderRole): String = role.wireName

    private fun isOfficialOpenAi(baseUrl: String): Boolean = runCatching {
        URI(baseUrl).host.equals("api.openai.com", ignoreCase = true)
    }.getOrDefault(false)
}

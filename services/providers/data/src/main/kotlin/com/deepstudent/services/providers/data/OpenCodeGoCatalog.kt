package com.deepstudent.services.providers.data

import com.deepstudent.platform.network.NetworkHeader
import com.deepstudent.services.providers.api.ResolvedProviderConfig
import java.net.URI
import java.util.Locale

/**
 * OpenCode Go subscription gateway constants and the built-in model catalog,
 * mirroring the frozen desktop `llm/src/opencode_go.ts` and `data/builtin_catalog.ts`.
 */
object OpenCodeGoCatalog {
    const val VENDOR_ID: String = "builtin-opencode-go"
    const val PROVIDER_TYPE: String = "opencode_go"
    const val OPENAI_BASE_URL: String = "https://opencode.ai/inference/go/openai/v1"
    const val ANTHROPIC_BASE_URL: String = "https://opencode.ai/inference/go/anthropic/v1"
    const val GOOGLE_BASE_URL: String = "https://opencode.ai/inference/go/google/v1beta"
    const val SESSION_HEADER: String = "x-opencode-session"
    const val TASK_SESSION_ID: String = "deepstudent-task"
    const val USER_AGENT_PREFIX: String = "DeepStudent/"

    /** Frozen session header budget; longer values are truncated after sanitization. */
    const val SESSION_HEADER_MAX_LENGTH: Int = 128

    private const val GO_HOST: String = "opencode.ai"
    private const val INFERENCE_PREFIX: String = "/inference/go"
    private const val ZEN_PREFIX: String = "/zen/go"

    fun userAgent(version: String): String = "$USER_AGENT_PREFIX$version"

    /** Vendor id, provider type or a Go root endpoint selects the OpenCode Go dialect. */
    fun isOpencodeGoConfig(vendorId: String?, providerType: String?, baseUrl: String): Boolean = vendorId == VENDOR_ID ||
        providerType?.trim()?.lowercase(Locale.ROOT) == PROVIDER_TYPE ||
        isGoRootUrl(baseUrl)

    /** Go roots swap the base per protocol; every other endpoint is used verbatim. */
    fun baseUrlForProtocol(currentBaseUrl: String, protocol: String): String {
        if (!isGoRootUrl(currentBaseUrl)) return currentBaseUrl
        return when (protocol) {
            "anthropic_messages" -> ANTHROPIC_BASE_URL
            "google_generate_content" -> GOOGLE_BASE_URL
            else -> OPENAI_BASE_URL
        }
    }

    private fun isGoRootUrl(baseUrl: String): Boolean {
        val uri = runCatching { URI(baseUrl.trim()) }.getOrNull() ?: return false
        if (uri.scheme == null || uri.host?.lowercase(Locale.ROOT) != GO_HOST) return false
        val path = (uri.rawPath ?: "").trimEnd('/')
        return path.startsWith(INFERENCE_PREFIX) || path.startsWith(ZEN_PREFIX)
    }

    /** Header values keep `[A-Za-z0-9_.:-]` only; an empty result sends no session header. */
    fun sessionHeaderValue(sessionId: String?): String? {
        val sanitized = buildString {
            for (character in (sessionId ?: "").trim()) if (character.isSessionCharacter()) append(character)
        }.take(SESSION_HEADER_MAX_LENGTH)
        return sanitized.ifEmpty { null }
    }

    private fun Char.isSessionCharacter(): Boolean = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9' ||
        this == '_' || this == '.' || this == ':' || this == '-'

    /**
     * Identity headers every OpenCode Go request must carry. The session value is the
     * sanitized conversation identifier; calls without one fall back to [TASK_SESSION_ID].
     */
    fun identityHeaders(sessionId: String?, version: String): List<Pair<String, String>> = buildList {
        add("User-Agent" to userAgent(version))
        sessionHeaderValue(sessionId)?.let { add(SESSION_HEADER to it) }
    }

    internal fun appendIdentity(
        headers: MutableList<NetworkHeader>,
        resolved: ResolvedProviderConfig,
        sessionId: String?,
        version: String,
    ) {
        if (!isOpencodeGoConfig(resolved.vendor.id, resolved.providerType, resolved.baseUrl)) return
        val session = sessionHeaderValue(sessionId) ?: TASK_SESSION_ID
        for ((name, value) in identityHeaders(session, version)) RelayHeaders.appendIfAbsent(headers, name, value)
    }

    /** Wire protocol each built-in catalog model uses at the Go gateway. */
    fun modelAdapterForProtocol(protocol: String): String = when (protocol) {
        "anthropic_messages" -> "anthropic"
        "google_generate_content" -> "google"
        else -> "general"
    }
}

/** One built-in OpenCode Go catalog entry, byte-equivalent to the frozen desktop table. */
data class OpenCodeGoBuiltinModel(
    val id: String,
    val label: String,
    val model: String,
    val protocol: String,
    val isMultimodal: Boolean,
    val isReasoning: Boolean,
    val maxOutputTokens: Int,
)

/** The exact 13-model OpenCode Go catalog; model lists must never touch the network for it. */
val OPENCODE_GO_BUILTIN_MODELS: List<OpenCodeGoBuiltinModel> = listOf(
    OpenCodeGoBuiltinModel(
        "builtin-opencode-go-deepseek-v4.1-flash",
        "DeepSeek V4.1 Flash (Go)",
        "deepseek-v4.1-flash",
        "openai_chat_completions",
        true,
        false,
        32_768,
    ),
    OpenCodeGoBuiltinModel(
        "builtin-opencode-go-deepseek-v4-pro",
        "DeepSeek V4 Pro (Go)",
        "deepseek-v4-pro",
        "openai_chat_completions",
        false,
        false,
        32_768,
    ),
    OpenCodeGoBuiltinModel("builtin-opencode-go-glm-5.3", "GLM-5.3 (Go)", "glm-5.3", "openai_chat_completions", false, false, 32_768),
    OpenCodeGoBuiltinModel(
        "builtin-opencode-go-glm-5.3-flash",
        "GLM-5.3-Flash (Go)",
        "glm-5.3-flash",
        "openai_chat_completions",
        false,
        false,
        32_768,
    ),
    OpenCodeGoBuiltinModel("builtin-opencode-go-kimi-k3", "Kimi K3 (Go)", "kimi-k3", "openai_chat_completions", false, false, 32_768),
    OpenCodeGoBuiltinModel(
        "builtin-opencode-go-kimi-k3.1-pro",
        "Kimi K3.1 Pro (Go)",
        "kimi-k3.1-pro",
        "openai_chat_completions",
        false,
        false,
        32_768,
    ),
    OpenCodeGoBuiltinModel(
        "builtin-opencode-go-mimo-v2.6-pro",
        "MiMo-V2.6-Pro (Go)",
        "mimo-v2.6-pro",
        "openai_chat_completions",
        false,
        false,
        32_768,
    ),
    OpenCodeGoBuiltinModel(
        "builtin-opencode-go-qwen3.8-max",
        "Qwen3.8 Max (Go)",
        "qwen3.8-max",
        "anthropic_messages",
        false,
        false,
        32_768,
    ),
    OpenCodeGoBuiltinModel(
        "builtin-opencode-go-qwen3.8-flash",
        "Qwen3.8 Flash (Go)",
        "qwen3.8-flash",
        "anthropic_messages",
        false,
        false,
        32_768,
    ),
    OpenCodeGoBuiltinModel("builtin-opencode-go-minimax-m3", "MiniMax M3 (Go)", "minimax-m3", "anthropic_messages", false, false, 32_768),
    OpenCodeGoBuiltinModel(
        "builtin-opencode-go-claude-haiku-5-5",
        "Claude Haiku 5.5 (Go)",
        "claude-haiku-5-5",
        "anthropic_messages",
        true,
        true,
        32_768,
    ),
    OpenCodeGoBuiltinModel("builtin-opencode-go-gpt-6-luna", "GPT 6 Luna (Go)", "gpt-6-luna", "openai_responses", true, true, 32_768),
    OpenCodeGoBuiltinModel(
        "builtin-opencode-go-gemini-4-argon",
        "Gemini 4 Argon (Go)",
        "gemini-4-argon",
        "google_generate_content",
        true,
        false,
        32_768,
    ),
)

package com.deepstudent.services.providers.backend

import com.deepstudent.services.providers.api.ModelAdapter
import com.deepstudent.services.providers.api.ModelProfile
import java.util.Locale

/** Named vendors served by the Chat Completions adapter catalog, not the protocol allowlist. */
object ChatCompletionsVendors {
    private val defaults = mapOf(
        "siliconflow" to ModelAdapter.GENERAL,
        "qwen" to ModelAdapter.QWEN,
        "zhipu" to ModelAdapter.ZHIPU,
        "doubao" to ModelAdapter.DOUBAO,
        "minimax" to ModelAdapter.MINIMAX,
        "moonshot" to ModelAdapter.MOONSHOT,
        "nvidia" to ModelAdapter.GENERAL,
        "mimo" to ModelAdapter.MIMO,
        "ernie" to ModelAdapter.ERNIE,
        "mistral" to ModelAdapter.MISTRAL,
    )
    fun supports(provider: String): Boolean = provider in defaults

    internal fun requestAdapter(provider: String, profile: ModelProfile): String? =
        defaults[provider]?.let { OpenAiAdapterScopes.resolve(provider, profile, it).wireName }
}

internal object OpenAiAdapterScopes {
    private val scopeAdapters = ModelAdapter.entries.associateBy(ModelAdapter::wireName) + mapOf(
        "openai" to ModelAdapter.GENERAL,
        "kimi" to ModelAdapter.MOONSHOT,
        "baidu" to ModelAdapter.ERNIE,
        "claude" to ModelAdapter.ANTHROPIC,
        "gemini" to ModelAdapter.GOOGLE,
        "xai" to ModelAdapter.GROK,
    )
    private val aggregatorScopes = setOf(
        "custom", "general", "openrouter", "together", "fireworks", "groq", "one-api", "one_api", "sub2api", "cpa",
    )
    private val siliconFlowFamilies = setOf(ModelAdapter.DEEPSEEK, ModelAdapter.QWEN)
    private val nativeAdapters = setOf(ModelAdapter.ANTHROPIC, ModelAdapter.GOOGLE)

    fun resolve(provider: String, profile: ModelProfile, default: ModelAdapter): ModelAdapter {
        val scope = profile.providerScope?.trim()?.lowercase(Locale.ROOT)
        return when {
            profile.adapter in nativeAdapters -> ModelAdapter.GENERAL
            "siliconflow" in listOf(provider, scope) -> profile.adapter.takeIf { it in siliconFlowFamilies } ?: ModelAdapter.GENERAL
            scope in aggregatorScopes -> ModelAdapter.GENERAL
            else -> scopeAdapters[scope] ?: default
        }
    }
}

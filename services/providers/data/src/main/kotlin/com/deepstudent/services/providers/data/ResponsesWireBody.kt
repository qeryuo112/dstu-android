package com.deepstudent.services.providers.data

import com.deepstudent.core.common.json.JsonValue

/**
 * Encoded Responses POST payload plus the moderation flags frozen at send time
 * (`host/plugins/llm-providers/src/providers/openai-responses.ts` buildRequest at
 * e6b2657fd59014a73f5bbb1d14c2db732cf23e87). The decoder is constructed from the
 * flags, so they are part of the wire contract and not recomputable later.
 */
internal class ResponsesWireBody(val json: JsonValue.Object, val deepseekModerated: Boolean, val deepseekWebTools: Boolean)

/**
 * Blank sessions share the frozen background key
 * (`openai-codex/protocol.ts` stablePromptCacheKey / prompt_cache.ts FALLBACK).
 */
internal const val RESPONSES_BACKGROUND_SESSION = "deep-student-background"

/**
 * Frozen stablePromptCacheKey: one trimmed session id for the Codex session
 * headers and the Responses prompt_cache_key alike.
 */
internal fun codexSessionId(sessionId: String?): String {
    val trimmed = sessionId?.trim().orEmpty()
    return trimmed.ifEmpty { RESPONSES_BACKGROUND_SESSION }
}

package com.deepstudent.services.providers.data

import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.services.providers.api.ProviderNativeHistory
import com.deepstudent.services.providers.api.ProviderProtocol
import com.deepstudent.services.providers.api.ResolvedProviderConfig
import java.net.URI
import java.util.Locale

/*
 * Unit-owned S3.07 history ports. The shared ProviderMessage
 * contract stays sealed, so Gemini thought signatures and preprocessed image parts are
 * supplied through these locally injected ports instead of new shared fields.
 *
 * Frozen desktop sources: host/plugins/llm/src/reasoning_state_scope.ts (signature scope
 * binding) and host/plugins/llm/src/model2_messages.ts (user image parts as data URLs).
 */

/** A thought signature saved from a previous Gemini 3 turn, bound to its replay scope. */
data class GeminiSignedTurn(val signature: String, val stateScope: String?) {
    init {
        require(signature.isNotEmpty()) { "empty Gemini thought signature" }
    }

    override fun toString(): String = "GeminiSignedTurn(signature=redacted, scope=${stateScope != null})"
}

/** Supplies per-message thought signatures; index is the position in the request messages. */
fun interface GeminiThoughtSignatureSource {
    fun signatureFor(messageIndex: Int): GeminiSignedTurn?
}

/** A preprocessed image for a user turn. Remote URLs are never downloaded here. */
sealed interface GeminiImagePart {
    /** Already-inlined `data:` URL produced by the caller's preprocessing step. */
    data class DataUrl(val url: String) : GeminiImagePart

    /** Decoded-side inline payload; encoded to a `data:` URL before conversion. */
    data class Inline(val mimeType: String, val base64Data: String) : GeminiImagePart {
        init {
            require(mimeType.isNotBlank() && base64Data.isNotEmpty()) { "invalid inline image" }
        }
    }
}

/** Supplies preprocessed image parts for a user message; the port never fetches URLs. */
fun interface GeminiImageContentSource {
    fun imagesFor(messageIndex: Int): List<GeminiImagePart>
}

/** Optional attachments and signature observation; standard messages carry their own replay data. */
class GeminiNativePorts(
    val signatures: GeminiThoughtSignatureSource? = null,
    val images: GeminiImageContentSource? = null,
    val signatureSink: GeminiThoughtSignatureSink? = null,
)

/**
 * Same-protocol private state replay for this exact target only. A different
 * scope is not replay authorization, so the caller falls back to the legacy
 * ports (which keep the frozen unscoped policy). Only the signature is read;
 * tool calls stay with the caller's paired history so unexecuted calls are
 * never persisted here.
 */
internal fun ProviderNativeHistory.geminiSignedTurn(targetScope: String): GeminiSignedTurn? {
    if (protocol != ProviderProtocol.GOOGLE_GENERATE_CONTENT) return null
    if (stateScope != targetScope) return null
    val payload = runCatching { JsonValue.parse(payloadJson) as? JsonValue.Object }.getOrNull() ?: return null
    val signature = (payload["thoughtSignature"] as? JsonValue.Text)?.value?.takeIf(String::isNotEmpty) ?: return null
    return GeminiSignedTurn(signature, stateScope)
}

/**
 * Per-call success history: the most recent thought signature of this stream,
 * bound to the same target tuple. Returns null when there is nothing to replay
 * or the bounded DTO limit would be exceeded.
 */
internal fun geminiNativeHistory(targetScope: String, signature: String?): ProviderNativeHistory? {
    if (signature.isNullOrEmpty()) return null
    val payload = JsonValue.obj("thoughtSignature" to JsonValue.text(signature)).literal()
    if (payload.length > ProviderNativeHistory.MAX_PAYLOAD_CHARS) return null
    return ProviderNativeHistory(ProviderProtocol.GOOGLE_GENERATE_CONTENT, targetScope, payload)
}

/**
 * Replay scope of the frozen desktop: [provider, endpoint fingerprint, model, protocol].
 * Signatures are replayed only against the exact same target; mismatched scopes are
 * stripped before conversion so unprotected functionCall parts degrade to text.
 */
internal class GoogleGeminiScope(config: ResolvedProviderConfig) {
    val value: String = JsonValue.array(
        listOf(
            config.providerType,
            endpointFingerprint(config.baseUrl),
            config.profile.model.trim().lowercase(Locale.ROOT),
            config.protocol.wireName,
        ).map(JsonValue::text),
    ).literal()

    /**
     * Same-target signatures stay; legacy unscoped signatures keep the desktop's
     * replay policy; a mismatched scope is dropped so the conversion degrades the
     * unprotected functionCall parts to text.
     */
    fun replayable(turn: GeminiSignedTurn?): GeminiSignedTurn? = when {
        turn == null -> null
        turn.stateScope == null || turn.stateScope == value -> turn
        else -> null
    }

    private fun endpointFingerprint(value: String): String {
        val endpoint = URI(value)
        val defaultPort = when (endpoint.scheme) {
            "https" -> 443
            "http" -> 80
            else -> -1
        }
        val port = if (endpoint.port < 0 || endpoint.port == defaultPort) "" else ":${endpoint.port}"
        val normalized = "${endpoint.scheme}://${endpoint.host}$port${endpoint.rawPath}".trimEnd('/')
        var hash = 0x811c9dc5u
        for (byte in normalized.encodeToByteArray()) hash = (hash xor byte.toUByte().toUInt()) * 0x01000193u
        return hash.toString(16).padStart(8, '0')
    }
}

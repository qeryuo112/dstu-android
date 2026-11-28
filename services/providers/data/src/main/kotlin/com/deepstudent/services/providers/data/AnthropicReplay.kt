package com.deepstudent.services.providers.data

import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.services.providers.api.ResolvedProviderConfig
import java.net.URI
import java.util.Locale

/*
 * Dedicated Anthropic replay sidecar (`llm/src/reasoning_state_scope.ts` at
 * e6b2657fd59014a73f5bbb1d14c2db732cf23e87): thinking signatures and images are
 * private provider state that must never ride the shared sealed stream API or
 * the Responses history type, so they travel through these Anthropic-named
 * carriers only.
 */

/** Bytes and media type resolved for one image part; never logged. */
class AnthropicImageBytes(val mediaType: String, val base64: String) {
    override fun toString(): String = "AnthropicImageBytes(redacted)"
}

/**
 * Controlled image lookup. Implementations return bytes that were already
 * fetched by the caller; the adapter itself never performs network I/O, and a
 * miss drops the image part (the frozen fetch-failure path).
 */
fun interface AnthropicImageSource {
    fun fetch(url: String): AnthropicImageBytes?
}

/**
 * Replay blocks captured from a previous same-target stream, keyed by the
 * assistant message index they belong to. `scope` binds them to one provider,
 * endpoint fingerprint, model and protocol tuple.
 */
class AnthropicReplayState(val scope: String, val blocksByMessageIndex: Map<Int, String>) {
    override fun toString(): String = "AnthropicReplayState(scope=$scope, blocks=redacted)"
}

/**
 * Per-request sidecar injected only through the concrete Anthropic port. A
 * `null` sidecar keeps the plain [ProviderStreamPort] path sidecar-free.
 */
class AnthropicRequestSidecar(
    val replay: AnthropicReplayState? = null,
    val imageSource: AnthropicImageSource? = null,
    val imageUrls: Map<Int, List<String>> = emptyMap(),
) {
    override fun toString(): String = "AnthropicRequestSidecar(redacted)"
}

/** Captured same-target replay state handed back to the caller after a stream. */
class AnthropicReplayCapture(val stateScope: String, val blocksByMessageIndex: Map<Int, String>) {
    override fun toString(): String = "AnthropicReplayCapture(scope=$stateScope, blocks=redacted)"
}

/** Read side of the concrete port: the replay state of the last completed turn. */
interface AnthropicReplayCarrier {
    fun replayCapture(): AnthropicReplayCapture?
}

/**
 * Serialized provider / endpoint fingerprint / model / effective-protocol
 * tuple (`computeReasoningStateScope`); signatures replay only on an exact
 * match, and the endpoint text itself never enters the persisted scope.
 */
internal object AnthropicHistoryScope {
    fun compute(resolved: ResolvedProviderConfig): String {
        val provider = resolved.providerType.trim().ifEmpty { resolved.providerScope.trim() }
            .lowercase(Locale.ROOT)
        val entries = listOf(
            provider,
            reasoningScopeHash(endpointIdentity(resolved.baseUrl)),
            resolved.profile.model.trim().lowercase(Locale.ROOT),
            resolved.protocol.wireName,
        )
        val body = entries.joinToString(",") { jsonText(it) }
        return "[$body]"
    }

    fun canReplay(savedScope: String?, targetScope: String): Boolean = savedScope != null && savedScope == targetScope

    private fun jsonText(value: String): String = JsonValue.text(value).literal()

    /** URL identity without credentials or fragment; path case is preserved. */
    private fun endpointIdentity(value: String): String {
        val trimmed = value.trim().trimEnd('/')
        val endpoint = runCatching { URI(trimmed) }.getOrElse { return trimmed }
        val defaultPort = when (endpoint.scheme) {
            "https" -> 443
            "http" -> 80
            else -> -1
        }
        val port = if (endpoint.port < 0 || endpoint.port == defaultPort) "" else ":${endpoint.port}"
        val host = endpoint.host?.lowercase(Locale.ROOT) ?: return trimmed
        return "${endpoint.scheme}://$host$port${endpoint.rawPath.orEmpty()}".trimEnd('/')
    }

    /** Portable FNV-1a 32 fingerprint (`reasoningScopeHash`), 8 lowercase hex digits. */
    internal fun reasoningScopeHash(value: String): String {
        var hash = 0x811c9dc5u
        for (byte in value.encodeToByteArray()) {
            hash = (hash xor byte.toUByte().toUInt()) * 0x01000193u
        }
        return hash.toUInt().toString(16).padStart(8, '0')
    }
}

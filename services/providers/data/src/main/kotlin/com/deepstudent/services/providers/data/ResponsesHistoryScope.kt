package com.deepstudent.services.providers.data

import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.services.providers.api.ProviderAdapterRequest
import com.deepstudent.services.providers.api.ProviderEventSink
import com.deepstudent.services.providers.api.ProviderMessage
import com.deepstudent.services.providers.api.ProviderStreamEvent
import com.deepstudent.services.providers.api.ResolvedProviderConfig
import java.net.URI
import java.util.Locale

/** Replies are bound to the same route tuple as scoped state emitted by the frozen desktop. */
internal class ResponsesHistoryScope(config: ResolvedProviderConfig) {
    private val scope = JsonValue.array(
        listOf(
            config.providerType,
            endpointFingerprint(config.baseUrl),
            config.profile.model.trim().lowercase(Locale.ROOT),
            config.protocol.wireName,
        )
            .map(JsonValue::text),
    ).literal()

    fun sanitize(request: ProviderAdapterRequest): ProviderAdapterRequest {
        val messages = request.messages.map(::sanitizeMessage)
        if (messages == request.messages) return request
        return ProviderAdapterRequest(request.resolved, messages, request.tools, request.sessionId)
    }

    fun annotate(sink: ProviderEventSink) = ProviderEventSink { event ->
        sink.emit(if (event is ProviderStreamEvent.ResponsesMetadata) event.copy(stateScope = scope) else event)
    }

    private fun sanitizeMessage(message: ProviderMessage): ProviderMessage {
        val history = message.responses ?: return message
        // Match the desktop's legacy unscoped history policy; new stream items are always annotated.
        if (history.stateScope == null || history.stateScope == scope) return message
        return message.copy(
            responses = history.copy(reasoningItemJson = null, webSearchItemsJson = null, reasoningItemsJson = null, stateScope = null),
        )
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

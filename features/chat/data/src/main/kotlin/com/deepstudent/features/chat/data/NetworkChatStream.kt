package com.deepstudent.features.chat.data

import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.features.chat.api.ChatStreamRequest
import com.deepstudent.features.chat.api.ChatStreamTransport
import com.deepstudent.platform.network.NetworkHeader
import com.deepstudent.platform.network.NetworkRequest
import com.deepstudent.platform.network.NetworkTransport
import com.deepstudent.platform.network.SseEnd
import java.net.URI

/** Minimal SSE transport, not a provider credential store or full chat-turn pipeline. */
class NetworkChatStream(
    private val network: NetworkTransport,
    private val allowLoopback: Boolean = false,
) : ChatStreamTransport {
    override suspend fun collect(request: ChatStreamRequest, emit: suspend (String) -> Unit) {
        validateEndpoint(request.endpoint)
        val body = JsonValue.Object.Builder().put("prompt", request.prompt).put("stream", true).build()
        var done = false
        val end = network.streamSse(NetworkRequest(
            request.endpoint, "POST", listOf(NetworkHeader("Content-Type", "application/json")),
            body.literal().toByteArray(Charsets.UTF_8),
        )) { event ->
            if (event.data == "[DONE]") done = true
            else {
                check(!done) { "data after stream completion" }
                decodeDelta(event.data)?.let { emit(it) }
            }
        }
        check(end == SseEnd.COMPLETE && done) { "incomplete chat stream" }
    }

    private fun validateEndpoint(endpoint: String) {
        val uri = URI(endpoint)
        val loopback = allowLoopback && uri.scheme == "http" && uri.host in setOf("127.0.0.1", "localhost", "[::1]")
        require((uri.scheme == "https" || loopback) && uri.host != null)
        require(uri.rawUserInfo == null && uri.rawFragment == null && uri.rawQuery == null)
    }
}

internal fun decodeDelta(data: String): String? {
    val event = JsonValue.parseObject(data)
    (event["delta"] as? JsonValue.Text)?.let { return it.value }
    val choices = (event["choices"] as? JsonValue.Array)?.values ?: return null
    val choice = choices.firstOrNull() as? JsonValue.Object ?: return null
    val delta = choice["delta"] as? JsonValue.Object ?: return null
    return (delta["content"] as? JsonValue.Text)?.value
}

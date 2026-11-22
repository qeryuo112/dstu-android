package com.deepstudent.features.chat.data

import com.deepstudent.features.chat.api.ChatStreamRequest
import com.deepstudent.platform.network.OkHttpNetworkTransport
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkChatStreamTest {
    @Test fun loopbackSseDecodesRealNetworkEventsAndRequestBody() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                "data: {\"choices\":[{\"delta\":{\"content\":\"中\"}}]}\n\n" +
                    "data: {\"delta\":\"文\"}\n\ndata: [DONE]\n\n"))
            OkHttpNetworkTransport().use { network ->
                val text = StringBuilder()
                NetworkChatStream(network, true).collect(ChatStreamRequest(server.url("/sse").toString(), "消息")) { text.append(it) }
                assertEquals("中文", text.toString())
                assertTrue(server.takeRequest().body.readUtf8().contains("消息"))
            }
        }
    }

    @Test fun cleartextIsRejectedByDefaultBeforeNetworkRequest() = runTest {
        OkHttpNetworkTransport().use { network ->
            try {
                NetworkChatStream(network).collect(ChatStreamRequest("http://127.0.0.1:1/sse", "message")) {}
                error("cleartext accepted")
            } catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun truncatedStreamIsNotMarkedComplete() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody("data: {\"delta\":\"part\"}\n\n"))
            OkHttpNetworkTransport().use { network ->
                try {
                    NetworkChatStream(network, true).collect(ChatStreamRequest(server.url("/sse").toString(), "message")) {}
                    error("truncated stream accepted")
                } catch (_: IllegalStateException) { }
            }
        }
    }
}

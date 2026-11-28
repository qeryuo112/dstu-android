package com.deepstudent.platform.network

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SseRejectionBodyTest {
    @Test
    fun optionalBodyReaderIsStatusSpecificBoundedAndWipesItsBuffer() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401).setBody("unsolicited private error"))
            server.enqueue(MockResponse().setResponseCode(404).setBody("quota"))
            server.enqueue(MockResponse().setResponseCode(404).setChunkedBody("oversized", 1))
            OkHttpNetworkTransport().use { transport ->
                val request = NetworkRequest(server.url("/responses").toString())
                val inspected = mutableListOf<ByteArray>()
                val limits = SseLimits(
                    rejectionBody = SseRejectionBody(404, HttpLimits(5)) { body ->
                        assertEquals("quota", body.decodeToString())
                        inspected += body
                    },
                )
                assertEquals(SseEnd.REJECTED_HTTP_STATUS, transport.streamSse(request, limits) { error("unexpected SSE frame") })
                assertTrue(inspected.isEmpty())
                assertEquals(SseEnd.REJECTED_HTTP_STATUS, transport.streamSse(request, limits) { error("unexpected SSE frame") })
                assertTrue(inspected.single().all { it == 0.toByte() })
                val error = runCatching { transport.streamSse(request, limits) { error("unexpected SSE frame") } }.exceptionOrNull()
                assertEquals(NetworkError.RESPONSE_TOO_LARGE, (error as NetworkException).error)
                assertEquals(1, inspected.size)
            }
        }
    }
}

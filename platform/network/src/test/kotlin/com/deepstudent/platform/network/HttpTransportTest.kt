package com.deepstudent.platform.network

import java.util.Collections
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpTransportTest {
    @Test
    fun actualHttpRoundTripPreservesBytesHeadersAndNonSuccessStatus() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(409).addHeader("X-Repeat", "a")
                .addHeader("X-Repeat", "b").setBody(Buffer().write(byteArrayOf(0, -1, 42))))
            OkHttpNetworkTransport().use { transport ->
                val request = NetworkRequest(server.url("/objects?q=1").toString(), "PUT",
                    listOf(NetworkHeader("X-Request", "value")), byteArrayOf(4, 0, -1))
                val response = transport.execute(request)
                assertEquals(409, response.head.statusCode)
                assertArrayEquals(byteArrayOf(0, -1, 42), response.body)
                assertEquals(listOf("a", "b"), response.head.headers.filter { it.name == "X-Repeat" }.map { it.value })
                val captured = server.takeRequest()
                assertEquals("PUT", captured.method)
                assertEquals("/objects?q=1", captured.path)
                assertEquals("value", captured.getHeader("X-Request"))
                assertArrayEquals(byteArrayOf(4, 0, -1), captured.body.readByteArray())
            }
        }
    }

    @Test
    fun responseBudgetAppliesToAnnouncedAndChunkedBodies() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("12345"))
            server.enqueue(MockResponse().setChunkedBody("12345", 1))
            OkHttpNetworkTransport().use { transport ->
                repeat(2) {
                    assertNetworkError(NetworkError.RESPONSE_TOO_LARGE) {
                        transport.execute(NetworkRequest(server.url("/").toString()), HttpLimits(4))
                    }
                }
            }
        }
    }

    @Test
    fun actualSseResponseReplaysRecordingAndReportsTruncation() = runBlocking {
        val bytes = recordingBytes()
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream; charset=utf-8")
                .setChunkedBody(Buffer().write(bytes), 1))
            val events = mutableListOf<SseEvent>()
            var status = 0
            OkHttpNetworkTransport().use { transport ->
                val end = transport.streamSse(NetworkRequest(server.url("/").toString()),
                    onResponse = { status = it.statusCode }, onEvent = { events.add(it) })
                assertEquals(200, status)
                assertEquals(SseEnd.TRUNCATED, end)
            }
            assertEquals("中文\nline two", events.single().data)
            assertEquals("update", events.single().type)
            assertEquals("42", events.single().lastEventId)
        }
    }

    @Test
    fun rejectedStatusAndContentTypeAreDistinguished() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401).setBody("private"))
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("private"))
            OkHttpNetworkTransport().use { transport ->
                var status = 0
                val rejected = transport.streamSse(NetworkRequest(server.url("/").toString()),
                    onResponse = { status = it.statusCode }, onEvent = { error("unexpected event") })
                assertEquals(401, status)
                assertEquals(SseEnd.REJECTED_HTTP_STATUS, rejected)
                assertNetworkError(NetworkError.SSE_INVALID_CONTENT_TYPE) {
                    transport.streamSse(NetworkRequest(server.url("/").toString()), onEvent = {})
                }
            }
        }
    }

    @Test
    fun brokenHttpBodyIsFailureNotCleanEof() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("1234567890").setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
            OkHttpNetworkTransport().use { transport ->
                assertNetworkError(NetworkError.CONNECTION) {
                    transport.execute(NetworkRequest(server.url("/").toString()))
                }
            }
        }
    }

    @Test
    fun brokenSseTransportIsFailureNotCleanEof() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody("data: x\n\ndata: unfinished")
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
            OkHttpNetworkTransport().use { transport ->
                assertNetworkError(NetworkError.CONNECTION) {
                    transport.streamSse(NetworkRequest(server.url("/").toString()), onEvent = {})
                }
            }
        }
    }

    @Test
    fun streamedFramesEnforceBudgetAndRejectInvalidUtf8() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
                .setChunkedBody("data: oversized\n\n", 1))
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
                .setChunkedBody(Buffer().write(byteArrayOf(0xc3.toByte(), 0x28, 10, 10)), 1))
            OkHttpNetworkTransport().use { transport ->
                val request = NetworkRequest(server.url("/").toString())
                assertNetworkError(NetworkError.SSE_TOO_LARGE) {
                    transport.streamSse(request, SseLimits(maxLineBytes = 8), onEvent = {})
                }
                assertNetworkError(NetworkError.SSE_INVALID_UTF8) {
                    transport.streamSse(request, onEvent = {})
                }
            }
        }
    }

    @Test
    fun secretsCannotAppearInErrorsLogsOrDefaultStringRepresentations() = runBlocking {
        val secret = "S1-07-secret-sentinel"
        val logs = Collections.synchronizedList(mutableListOf<NetworkLogEvent>())
        OkHttpNetworkTransport(NetworkLog { logs.add(it) }).use { transport ->
            val request = NetworkRequest("http://$secret:pass@127.0.0.1/$secret?$secret", "GET",
                listOf(NetworkHeader("Authorization", secret)), secret.toByteArray())
            var failure: NetworkException? = null
            try {
                transport.execute(request)
            } catch (caught: NetworkException) {
                failure = caught
            }
            assertEquals(NetworkError.INVALID_REQUEST, failure!!.error)
            assertNull(failure.cause)
            assertFalse(listOf(request.toString(), request.headers.toString(), failure.toString(), logs.toString())
                .any { secret in it })
            assertTrue(logs.map { it.phase }.containsAll(listOf(NetworkPhase.STARTED, NetworkPhase.FAILED)))
        }
    }

    @Test
    fun realSecretsAndCallbackFailuresStayOutOfDiagnostics() = runBlocking {
        val secret = "S1-07-secret-sentinel"
        val logs = mutableListOf<NetworkLogEvent>()
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody("data: $secret\n\n"))
            val request = NetworkRequest(server.url("/$secret?token=$secret").toString(),
                headers = listOf(NetworkHeader("Authorization", secret)))
            OkHttpNetworkTransport(NetworkLog { logs.add(it) }).use { transport ->
                try {
                    transport.streamSse(request, onEvent = { error(secret) })
                    org.junit.Assert.fail("expected a sanitized error")
                } catch (failure: NetworkException) {
                    assertEquals(NetworkError.INTERNAL, failure.error)
                    assertNull(failure.cause)
                    assertTrue(failure.suppressed.isEmpty())
                    assertFalse(failure.stackTraceToString().contains(secret))
                }
            }
            assertEquals(secret, server.takeRequest().getHeader("Authorization"))
            assertFalse(logs.toString().contains(secret))
        }
    }

    @Test
    fun redirectsAreReturnedWithoutForwardingCredentials() = runBlocking {
        MockWebServer().use { destination ->
            MockWebServer().use { origin ->
                origin.enqueue(MockResponse().setResponseCode(302).addHeader("Location", destination.url("/")))
                val request = NetworkRequest(origin.url("/").toString(),
                    headers = listOf(NetworkHeader("Authorization", "private")))
                OkHttpNetworkTransport().use { transport -> assertEquals(302, transport.execute(request).head.statusCode) }
                assertEquals(0, destination.requestCount)
            }
        }
    }

    @Test
    fun loggerFailureDoesNotChangeNetworkOutcome() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("ok"))
            OkHttpNetworkTransport(NetworkLog { error("private") }).use { transport ->
                assertEquals("ok", transport.execute(NetworkRequest(server.url("/").toString())).body.toString(Charsets.UTF_8))
            }
        }
    }

    @Test
    fun valuesAreSnapshotsAndCallbacksCannotLeakExceptionDetails() = runBlocking {
        val original = byteArrayOf(1, 2)
        val request = NetworkRequest("http://127.0.0.1/", "PUT", body = original)
        original[0] = 9
        request.body!![1] = 9
        assertArrayEquals(byteArrayOf(1, 2), request.body)
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody("data: private\n\n"))
            OkHttpNetworkTransport().use { transport ->
                assertNetworkError(NetworkError.INTERNAL) {
                    transport.streamSse(NetworkRequest(server.url("/").toString()), onEvent = { error("private") })
                }
            }
        }
    }
}

internal fun recordingBytes(): ByteArray {
    val hex = HttpTransportTest::class.java.getResourceAsStream("/s1-07/sse-recording.hex")!!
        .bufferedReader().use { it.readText().trim() }
    return hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}

internal suspend fun assertNetworkError(error: NetworkError, block: suspend () -> Unit) {
    try {
        block()
        org.junit.Assert.fail("expected a network failure")
    } catch (failure: NetworkException) {
        assertEquals(error, failure.error)
        assertNull(failure.cause)
        assertTrue(failure.suppressed.isEmpty())
    }
}

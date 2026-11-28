package com.deepstudent.platform.network

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

/**
 * A 407 challenge body belongs to the proxy hop, never to the app protocol. OkHttp only routes
 * 407 to an authenticator when a proxy is configured and otherwise fails a direct-origin 407 as
 * a protocol error, so the transport normalizes both into one fixed failure before a caller can
 * see the status, the challenge headers or the body bytes.
 */
class ProxyAuthenticationTest {
    @Test
    fun rejectsProxyAuthenticationWithoutReportingUpstreamBody() = runBlocking {
        val secret = "S3-08-proxy-credential-sentinel"
        val logs = mutableListOf<NetworkLogEvent>()
        loopbackServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(407)
                    .addHeader("Proxy-Authenticate", "Basic realm=\"$secret\"")
                    .setBody("$secret proxy-only challenge body"),
            )
            OkHttpNetworkTransport(NetworkLog { logs.add(it) }).use { transport ->
                var failure: NetworkException? = null
                try {
                    transport.execute(
                        NetworkRequest(
                            loopbackUrl(server, "/v1/models"),
                            headers = listOf(NetworkHeader("Authorization", secret)),
                        ),
                    )
                } catch (caught: NetworkException) {
                    failure = caught
                }
                assertNotNull("a 407 must fail execute()", failure)
                assertEquals(1, server.requestCount)
                assertEquals(secret, server.takeRequest().getHeader("Authorization"))
                val rejected = failure!!
                assertEquals(NetworkError.PROXY_AUTHENTICATION, rejected.error)
                assertEquals(NetworkError.PROXY_AUTHENTICATION.fixedMessage, rejected.message)
                assertNull(rejected.cause)
                assertTrue(rejected.suppressed.isEmpty())
                assertFalse(rejected.toString().contains(secret))
                assertFalse(rejected.stackTraceToString().contains(secret))
                assertFalse(logs.toString().contains(secret))
                assertEquals(listOf(NetworkPhase.STARTED, NetworkPhase.FAILED), logs.map { it.phase })
                assertTrue(logs.all { it.operation == NetworkOperation.HTTP })
                assertNull(logs.first().error)
                assertEquals(NetworkError.PROXY_AUTHENTICATION, logs.last().error)
                assertEquals(logs.first().correlationId, logs.last().correlationId)
            }
        }
    }

    @Test
    fun streamRejectsProxyAuthenticationWithoutReportingUpstreamBody() = runBlocking {
        val secret = "S3-08-proxy-credential-sentinel"
        val logs = mutableListOf<NetworkLogEvent>()
        loopbackServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(407)
                    .addHeader("Proxy-Authenticate", "Basic realm=\"$secret\"")
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody("data: $secret proxy-only challenge body\n\n"),
            )
            OkHttpNetworkTransport(NetworkLog { logs.add(it) }).use { transport ->
                var responded = false
                val failure = runCatching {
                    transport.streamSse(
                        NetworkRequest(loopbackUrl(server, "/v1/responses")),
                        onResponse = { responded = true },
                        onEvent = { error("unexpected SSE event") },
                    )
                }.exceptionOrNull()
                assertTrue("a 407 must fail streamSse()", failure is NetworkException)
                assertFalse("a rejected exchange has no response head", responded)
                assertEquals(1, server.requestCount)
                val rejected = failure as NetworkException
                assertEquals(NetworkError.PROXY_AUTHENTICATION, rejected.error)
                assertNull(rejected.cause)
                assertFalse(rejected.stackTraceToString().contains(secret))
                assertFalse(logs.toString().contains(secret))
                assertEquals(listOf(NetworkPhase.STARTED, NetworkPhase.FAILED), logs.map { it.phase })
                assertTrue(logs.all { it.operation == NetworkOperation.SSE })
                assertNull(logs.first().error)
                assertEquals(NetworkError.PROXY_AUTHENTICATION, logs.last().error)
                assertEquals(logs.first().correlationId, logs.last().correlationId)
            }
        }
    }
}

/** Binds a throwaway loopback port so no test ever depends on external networking. */
private fun loopbackServer(): MockWebServer = MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 0) }

private fun loopbackUrl(server: MockWebServer, path: String): String = "http://127.0.0.1:${server.port}$path"

package com.deepstudent.app.runtime

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.deepstudent.app.DstuApplication
import com.deepstudent.features.chat.api.ModelPlaygroundFailure
import com.deepstudent.features.chat.api.ModelPlaygroundTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * S3.08 relay boundary for the development model playground port.
 *
 * Both cases call the real `runtime().providers.playground` port of the isolated debug app and talk
 * only to a MockWebServer bound to the IPv4 loopback address. No external network, real credential
 * or synthetic failure body may cross the port boundary.
 */
@RunWith(AndroidJUnit4::class)
class S308RelayBoundaryDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = DstuApplication.from(instrumentation.targetContext)

    @Before
    fun requireIsolatedTarget() {
        assertEquals(ISOLATED_APPLICATION_ID, instrumentation.targetContext.packageName)
    }

    @Test(timeout = 60_000)
    fun modelsProxyAuthenticationIsRedactedToAFixedCode() = runBlocking {
        val port = checkNotNull(app.runtime().providers.playground)
        val peer = MockWebServer()
        try {
            peer.start(InetAddress.getByName(LOOPBACK), 0)
            peer.enqueue(
                MockResponse()
                    .setResponseCode(407)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"error":"$SYNTHETIC_BODY"}"""),
            )
            val target = ModelPlaygroundTarget(
                provider = PROVIDER,
                endpoint = "http://$LOOPBACK:${peer.port}/v1",
                model = "",
                authMode = AUTH_NONE,
            )

            val failure = runCatching { port.models(target) }.exceptionOrNull()
            val playgroundFailure = failure as? ModelPlaygroundFailure
                ?: throw AssertionError("expected ModelPlaygroundFailure, got $failure")
            assertEquals(PROXY_AUTHENTICATION, playgroundFailure.code)
            assertNull(playgroundFailure.message)
            assertFalse(playgroundFailure.toString().contains(SYNTHETIC_BODY))

            val request = checkNotNull(peer.takeRequest(20, TimeUnit.SECONDS))
            assertEquals("$LOOPBACK:${peer.port}", request.getHeader("Host"))
            assertTrue(request.path.orEmpty().startsWith("/v1/models"))
            assertNull(request.getHeader("Authorization"))
            assertEquals(1, peer.requestCount)
        } finally {
            peer.shutdown()
        }
    }

    @Test(timeout = 60_000)
    fun streamSendsTheCustomHeaderThenCancelsWithoutASecondRequestOrChatRow() = runBlocking {
        val port = checkNotNull(app.runtime().providers.playground)
        val sessionId = "relay-cancel-${System.nanoTime()}"
        val session = "sess_playground_$sessionId"
        val peer = MockWebServer()
        try {
            peer.start(InetAddress.getByName(LOOPBACK), 0)
            peer.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val emitted = mutableListOf<String>()
            val target = ModelPlaygroundTarget(
                provider = PROVIDER,
                endpoint = "http://$LOOPBACK:${peer.port}/v1",
                model = MODEL_ID,
                authMode = AUTH_NONE,
                headers = mapOf(CLIENT_HEADER to CLIENT_TAG),
            )

            val call = async(Dispatchers.Default) {
                port.stream(target, PROMPT, sessionId) { emitted += it }
            }
            val request = withContext(Dispatchers.IO) { peer.takeRequest(20, TimeUnit.SECONDS) }
            assertNotNull(request)
            assertEquals("$LOOPBACK:${peer.port}", request!!.getHeader("Host"))
            assertTrue(request.path.orEmpty().startsWith("/v1/chat/completions"))
            assertEquals(CLIENT_TAG, request.getHeader(CLIENT_HEADER))

            call.cancelAndJoin()
            assertTrue(call.isCancelled)

            assertNull(withContext(Dispatchers.IO) { peer.takeRequest(1, TimeUnit.SECONDS) })
            assertEquals(1, peer.requestCount)
            assertTrue(emitted.isEmpty())
            // A cancelled call must settle without committing the playground session.
            delay(250)
            assertNull(app.storage().chatRepository.load(session))
        } finally {
            peer.shutdown()
        }
    }

    private companion object {
        const val ISOLATED_APPLICATION_ID = "com.deepstudent.app.s2device"
        const val PROVIDER = "custom"
        const val AUTH_NONE = "none"
        const val MODEL_ID = "local-s308-relay-model"
        const val PROMPT = "relay boundary prompt"
        const val CLIENT_HEADER = "X-Client-Tag"
        const val CLIENT_TAG = "s308-relay-boundary"
        const val PROXY_AUTHENTICATION = "proxy-authentication"
        const val SYNTHETIC_BODY = "synthetic-proxy-body-must-not-escape"
        const val LOOPBACK = "127.0.0.1"
    }
}

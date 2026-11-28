package com.deepstudent.app.runtime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.Socket

class S305CodexLoopbackTest {
    @Test
    fun onlyExpectedRelativeGetReachesAccountPortAndBrowserReplyIsNotReflective() = runBlocking {
        withTimeout(6_000) {
            checkNotNull(CodexLoopbackListener.bind(0)).use { listener ->
                val handled = mutableListOf<String>()
                val serving = async {
                    listener.serve { callback ->
                        handled += callback
                        if (callback.endsWith("state=expected")) CodexCallbackDisposition.COMPLETE else CodexCallbackDisposition.INVALID
                    }
                }
                assertRejected(listener, "GET /other?state=expected")
                assertRejected(listener, "POST /auth/callback?state=expected")
                assertRejected(listener, "GET http://attacker.invalid/auth/callback?state=expected")
                assertRejected(listener, "GET /auth/callback?code=bad-synthetic-code&state=incorrect")
                val authorized = request(listener, "GET /auth/callback?code=nonreflected-synthetic-code&state=expected")
                assertTrue(authorized.startsWith("HTTP/1.1 200 OK"))
                assertTrue(authorized.contains("Cache-Control: no-store"))
                assertFalse(authorized.contains("nonreflected-synthetic-code"))
                serving.await()
                assertEquals(2, handled.size)
                assertTrue(handled.all { it.startsWith("http://localhost:${listener.port}/auth/callback?") })
            }
        }
    }

    @Test
    fun closingTheListenerReleasesABlockedAccept() = runBlocking {
        withTimeout(2_000) {
            checkNotNull(CodexLoopbackListener.bind(0)).use { listener ->
                val serving = async { listener.serve { error("unexpected callback") } }
                listener.close()
                serving.await()
            }
        }
    }

    private suspend fun assertRejected(listener: CodexLoopbackListener, line: String) {
        val rejected = request(listener, line)
        assertTrue(rejected.startsWith("HTTP/1.1 400 Bad Request"))
        assertFalse(rejected.contains("bad-synthetic-code"))
    }

    private suspend fun request(listener: CodexLoopbackListener, line: String): String = withContext(Dispatchers.IO) {
        Socket(InetAddress.getByName("127.0.0.1"), listener.port).use { socket ->
            socket.soTimeout = 3_000
            socket.getOutputStream().write("$line HTTP/1.1\r\nHost: localhost:${listener.port}\r\n\r\n".toByteArray())
            socket.getInputStream().readBytes().decodeToString()
        }
    }
}

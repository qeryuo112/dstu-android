package com.deepstudent.services.sync.data

import com.deepstudent.services.sync.api.SyncError
import com.deepstudent.services.sync.backend.SyncFailure
import com.deepstudent.services.sync.backend.SyncObjectLimits
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test

class WebDavHttpTest {
    @Test fun putCreatesRootAndEncodesPercent() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val p = WebDavProviderFactory(allowLoopbackHttp = true).open(server.url("/dav/").toString(), "u", "root", charArrayOf())
            repeat(3) { server.enqueue(MockResponse().setResponseCode(201)) }
            p.put("中%.txt", byteArrayOf(7))
            assertEquals("/dav", server.takeRequest().path)
            assertEquals("/dav/root", server.takeRequest().path)
            assertEquals("/dav/root/%E4%B8%AD%25.txt", server.takeRequest().path)
        }
    }

    @Test fun redirectsLimitsAndInvalidPaths() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val p = WebDavProviderFactory(allowLoopbackHttp = true).open(server.url("/").toString(), "u", "r", charArrayOf())
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "https://example.org"))
            try { p.get("a"); fail() } catch (e: SyncFailure) { assertEquals(SyncError.NETWORK, e.error); assertNull(e.cause) }
            server.enqueue(MockResponse().setBody("x").setHeader("Content-Length", SyncObjectLimits.WIRE_BYTES + 1))
            try { p.get("a"); fail() } catch (e: SyncFailure) { assertEquals(SyncError.LIMIT_EXCEEDED, e.error) }
            for (key in listOf("../a", "a//b", "/a", "a/./b")) {
                try { p.get(key); fail() } catch (e: SyncFailure) { assertEquals(SyncError.INVALID_CONFIGURATION, e.error) }
            }
        }
    }

    @Test fun cancelAfterHeadersClosesCall() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val p = WebDavProviderFactory(allowLoopbackHttp = true).open(server.url("/").toString(), "u", "r", charArrayOf())
            server.enqueue(MockResponse().setBody("long body").throttleBody(1, 500, TimeUnit.MILLISECONDS))
            val job = launch(Dispatchers.IO) { p.get("a") }
            assertNotNull(server.takeRequest(3, TimeUnit.SECONDS))
            delay(200)
            withTimeout(3000) { job.cancelAndJoin() }
        }
    }
}

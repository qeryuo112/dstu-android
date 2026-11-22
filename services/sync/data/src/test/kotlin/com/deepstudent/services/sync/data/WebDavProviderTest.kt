package com.deepstudent.services.sync.data

import com.deepstudent.services.sync.api.SyncError
import com.deepstudent.services.sync.backend.SyncFailure
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class WebDavProviderTest {
    @Test fun operationsAndAuthentication() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val provider = WebDavProviderFactory(allowLoopbackHttp = true).open(server.url("/dav/").toString(), "user", "root", "pass".toCharArray())
            server.enqueue(MockResponse().setBody("payload"))
            assertEquals("payload", provider.get("notes/中文.json")?.decodeToString())
            val request = server.takeRequest()
            assertEquals("/dav/root/notes/%E4%B8%AD%E6%96%87.json", request.path)
            assertTrue(request.getHeader("Authorization")!!.startsWith("Basic "))
            server.enqueue(MockResponse().setResponseCode(204))
            provider.delete("a")
            assertEquals("DELETE", server.takeRequest().method)
            server.enqueue(MockResponse().setResponseCode(401).setBody("password"))
            try { provider.get("a"); fail() } catch (failure: SyncFailure) {
                assertEquals(SyncError.AUTHENTICATION, failure.error)
                assertFalse(failure.toString().contains("password"))
            }
        }
    }

    @Test fun rejectsInsecureAndTraversal() {
        val factory = WebDavProviderFactory()
        try { factory.open("http://example.com", "", "", charArrayOf()); fail() } catch (failure: SyncFailure) {
            assertEquals(SyncError.INSECURE_ENDPOINT, failure.error)
        }
        try { factory.open("https://example.com", "", "../bad", charArrayOf()); fail() } catch (failure: SyncFailure) {
            assertEquals(SyncError.INVALID_CONFIGURATION, failure.error)
        }
    }
}

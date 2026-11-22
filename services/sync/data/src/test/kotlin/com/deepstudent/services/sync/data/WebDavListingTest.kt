package com.deepstudent.services.sync.data

import com.deepstudent.services.sync.api.SyncError
import com.deepstudent.services.sync.backend.SyncFailure
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class WebDavListingTest {
    private fun response(href: String, collection: Boolean = false, status: Int = 200) =
        "<d:response><d:href>$href</d:href><d:propstat><d:prop><d:resourcetype>${if (collection) "<d:collection/>" else ""}</d:resourcetype></d:prop><d:status>HTTP/1.1 $status OK</d:status></d:propstat></d:response>"
    private fun xml(content: String) = "<d:multistatus xmlns:d=\"DAV:\">$content</d:multistatus>"

    @Test fun recursiveListingAndPrefix() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val provider = WebDavProviderFactory(allowLoopbackHttp = true).open(server.url("/dav/").toString(), "u", "root", charArrayOf())
            server.enqueue(MockResponse().setResponseCode(207).setBody(xml(response("/dav/root/", true) + response("/dav/root/a/", true) + response("/dav/root/b.txt"))))
            server.enqueue(MockResponse().setResponseCode(207).setBody(xml(response("/dav/root/a/", true) + response("/dav/root/a/%E4%B8%AD%25.txt"))))
            assertEquals(listOf("a/中%.txt"), provider.list("a/"))
            assertEquals("1", server.takeRequest().getHeader("Depth"))
        }
    }

    @Test fun invalidUtf8Rejected() {
        val server = MockWebServer()
        server.use {
            server.start()
            val base = server.url("/dav/root/")
            val error = assertThrows(SyncFailure::class.java) { WebDavListing.parse(byteArrayOf(0xff.toByte()), base, base) }
            assertEquals(SyncError.INVALID_REPOSITORY, error.error)
        }
    }

    @Test fun invalidListingsFailClosed() = runBlocking {
        val self = response("/dav/root/", true)
        val bad = listOf(
            "<!DOCTYPE x [<!ENTITY e SYSTEM 'file:///etc/passwd'>]><x>&e;</x>",
            xml(self + response("https://evil.invalid/dav/root/x")),
            xml(self + response("/dav/root/a", status = 404)),
            xml(self + response("/dav/root/a") + response("/dav/root/a")),
            xml(self + response("/dav/other/a")),
            "<other xmlns:d='DAV:'>${response("/dav/root/", true)}</other>",
            xml(self + "<d:response><d:href>/dav/root/a</d:href><d:status>HTTP/1.1 200 OK</d:status></d:response>"),
            xml(self + response("/dav/root/a/", false)),
            xml(self + response("/dav/root/a%0A")),
            "<broken",
        )
        MockWebServer().use { server ->
            server.start()
            val provider = WebDavProviderFactory(allowLoopbackHttp = true).open(server.url("/dav/").toString(), "u", "root", charArrayOf())
            for (body in bad) {
                server.enqueue(MockResponse().setResponseCode(207).setBody(body))
                try { provider.list(""); fail() } catch (error: SyncFailure) { assertEquals(SyncError.INVALID_REPOSITORY, error.error) }
            }
        }
    }
}

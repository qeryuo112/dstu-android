package com.deepstudent.services.sync.data

import com.deepstudent.services.sync.api.SyncError
import com.deepstudent.services.sync.backend.SyncFailure
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit

class WebDavStreamingTest {
    @Test
    fun sameBytesReadAndWriteWithoutLosingConditionalHeader() = runBlocking {
        MockWebServer().use { server ->
            val provider = provider(server)
            val fixture = ByteArray(1_000_003) { (it % 251).toByte() }
            val source = OpenSource(fixture)
            server.enqueue(MockResponse().setResponseCode(201)) // MKCOL for /fixture
            server.enqueue(MockResponse().setResponseCode(201))
            provider.putStream("object", fixture.size.toLong(), true, source::open)
            assertEquals("MKCOL", server.takeRequest(2, TimeUnit.SECONDS)?.method)
            val put = checkNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            assertEquals("PUT", put.method)
            assertEquals("*", put.getHeader("If-None-Match"))
            assertArrayEquals(fixture, put.body.readByteArray())
            assertEquals(1, source.closes)
            server.enqueue(MockResponse().setChunkedBody(Buffer().write(fixture), 71_003))
            assertArrayEquals(fixture, provider.readStream("object", fixture.size, InputStream::readBytes))
            server.enqueue(MockResponse().setResponseCode(404))
            assertNull(provider.readStream("missing", 9, InputStream::readBytes))
        }
    }

    @Test
    fun rejectsChunkedOverrunAndReaderReturningBeforeEof() = runBlocking {
        MockWebServer().use { server ->
            val provider = provider(server)
            server.enqueue(MockResponse().setChunkedBody("123456789", 3))
            val oversized = assertThrows(SyncFailure::class.java) {
                runBlocking { provider.readStream("object", 8, InputStream::readBytes) }
            }
            assertEquals(SyncError.LIMIT_EXCEEDED, oversized.error)
            server.enqueue(MockResponse().setBody("123"))
            val unread = assertThrows(SyncFailure::class.java) {
                runBlocking { provider.readStream("object", 8, InputStream::read) }
            }
            assertEquals(SyncError.NETWORK, unread.error)
        }
    }

    private fun provider(server: MockWebServer) = WebDavProviderFactory(allowLoopbackHttp = true)
        .open(server.url("/").toString(), "fixture-user", "fixture", charArrayOf())

    private class OpenSource(private val bytes: ByteArray) {
        var closes = 0
            private set

        fun open(): InputStream = object : ByteArrayInputStream(bytes) {
            override fun close() {
                closes++
            }
        }
    }
}

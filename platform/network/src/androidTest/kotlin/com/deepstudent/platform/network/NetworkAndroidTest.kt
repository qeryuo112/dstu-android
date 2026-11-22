package com.deepstudent.platform.network

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NetworkAndroidTest {
    @Test
    fun actualDeviceHttpAndSseReplay() = runBlocking {
        AndroidPeer(false).use { peer ->
            OkHttpNetworkTransport().use { transport ->
                val events = mutableListOf<SseEvent>()
                val end = transport.streamSse(NetworkRequest(peer.url), onEvent = { events.add(it) })
                assertEquals(SseEnd.TRUNCATED, end)
                assertEquals("中文\nline two", events.single().data)
                assertEquals("42", events.single().lastEventId)
            }
            assertTrue(peer.awaitFinished())
        }
    }

    @Test
    fun actualDeviceCancellationClosesLiveConnection() = runBlocking {
        AndroidPeer(true).use { peer ->
            val seen = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Boolean>()
            OkHttpNetworkTransport().use { transport ->
                val job = launch {
                    try {
                        transport.streamSse(NetworkRequest(peer.url), onEvent = { seen.complete(Unit) })
                    } catch (failure: CancellationException) {
                        cancelled.complete(true)
                        throw failure
                    }
                }
                withTimeout(10000) { seen.await() }
                withTimeout(10000) { job.cancelAndJoin() }
                assertTrue(cancelled.await())
                assertTrue(peer.awaitFinished())
            }
        }
    }
}

/** Local HTTP peer exists only in instrumented tests. */
private class AndroidPeer(private val waitForDisconnect: Boolean) : AutoCloseable {
    private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    private val socket = AtomicReference<Socket?>()
    private val done = CountDownLatch(1)
    private val failure = AtomicReference<Throwable?>()
    val url = "http://127.0.0.1:${server.localPort}/stream"
    private val worker = thread(name = "s1-07-device-peer") {
        try {
            server.accept().use { peer ->
                socket.set(peer)
                peer.soTimeout = 12000
                val input = peer.getInputStream()
                var tail = ""
                while (!tail.endsWith("\r\n\r\n")) {
                    val next = input.read()
                    check(next != -1)
                    tail = (tail + next.toChar()).takeLast(4)
                }
                val output = peer.getOutputStream()
                output.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\n\r\n".toByteArray())
                val bytes = "id: 42\r\ndata: 中文\r\ndata: line two\r\n\r\n".toByteArray(Charsets.UTF_8)
                for (byte in bytes) {
                    output.write(byte.toInt())
                    output.flush()
                }
                if (waitForDisconnect) {
                    check(input.read() == -1)
                } else {
                    output.write("data: unfinished".toByteArray())
                    output.flush()
                }
                done.countDown()
            }
        } catch (caught: Exception) {
            failure.set(caught)
            done.countDown()
        }
    }

    suspend fun awaitFinished(): Boolean = withContext(Dispatchers.IO) {
        val completed = done.await(10, TimeUnit.SECONDS)
        failure.get()?.let { throw AssertionError("device peer failed", it) }
        completed
    }

    override fun close() {
        socket.get()?.close()
        server.close()
        worker.join(13000)
        check(!worker.isAlive) { "device peer did not stop" }
    }
}

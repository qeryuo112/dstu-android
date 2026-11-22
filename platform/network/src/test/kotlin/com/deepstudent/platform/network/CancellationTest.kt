package com.deepstudent.platform.network

import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CancellationTest {
    @Test
    fun cancellationWhileWaitingForHeadersDisconnectsTheServer() = runBlocking {
        DisconnectServer(sendHeaders = false).use { server ->
            OkHttpNetworkTransport().use { transport ->
                val ended = CompletableDeferred<Boolean>()
                val job = launch {
                    try {
                        transport.execute(NetworkRequest(server.url))
                    } catch (cancelled: CancellationException) {
                        ended.complete(true)
                        throw cancelled
                    }
                }
                assertTrue(server.awaitRequest())
                withTimeout(5000) { job.cancelAndJoin() }
                assertTrue(ended.await())
                assertTrue(server.awaitDisconnect())
            }
        }
    }

    @Test
    fun cancellationWhileReadingSseDisconnectsAndPropagatesCancellation() = runBlocking {
        DisconnectServer(sendHeaders = true).use { server ->
            val seen = CompletableDeferred<Unit>()
            val phases = mutableListOf<NetworkPhase>()
            OkHttpNetworkTransport(NetworkLog { synchronized(phases) { phases.add(it.phase) } }).use { transport ->
                val ended = CompletableDeferred<Boolean>()
                val job = launch {
                    try {
                        transport.streamSse(NetworkRequest(server.url), onEvent = { seen.complete(Unit) })
                    } catch (cancelled: CancellationException) {
                        ended.complete(true)
                        throw cancelled
                    }
                }
                withTimeout(5000) { seen.await() }
                withTimeout(5000) { job.cancelAndJoin() }
                assertTrue(ended.await())
                assertTrue(server.awaitDisconnect())
                assertTrue(synchronized(phases) { NetworkPhase.CANCELLED in phases })
            }
        }
    }

    @Test
    fun closeCancelsActiveCallAndRejectsNewCalls() = runBlocking {
        DisconnectServer(sendHeaders = true).use { server ->
            val seen = CompletableDeferred<Unit>()
            val transport = OkHttpNetworkTransport()
            val outcome = CompletableDeferred<NetworkError>()
            val job = launch {
                try {
                    transport.streamSse(NetworkRequest(server.url), onEvent = { seen.complete(Unit) })
                } catch (failure: NetworkException) {
                    outcome.complete(failure.error)
                }
            }
            withTimeout(5000) { seen.await() }
            transport.close()
            transport.close()
            withTimeout(5000) { job.join() }
            assertEquals(NetworkError.CONNECTION, outcome.await())
            assertTrue(server.awaitDisconnect())
            assertNetworkError(NetworkError.CLOSED) { transport.execute(NetworkRequest(server.url)) }
        }
    }

    @Test
    fun cancellationDuringSuspendedCallbackAlsoReleasesConnection() = runBlocking {
        DisconnectServer(sendHeaders = true).use { server ->
            val seen = CompletableDeferred<Unit>()
            OkHttpNetworkTransport().use { transport ->
                val job = launch {
                    transport.streamSse(NetworkRequest(server.url), onEvent = {
                        seen.complete(Unit)
                        awaitCancellation()
                    })
                }
                withTimeout(5000) { seen.await() }
                withTimeout(5000) { job.cancelAndJoin() }
                assertTrue(server.awaitDisconnect())
            }
        }
    }

    @Test
    fun repeatedCancellationRacesPropagateCancellationAndCloseSockets() = runBlocking {
        repeat(20) {
            DisconnectServer(sendHeaders = true).use { server ->
                val seen = CompletableDeferred<Unit>()
                val cancelled = CompletableDeferred<Boolean>()
                OkHttpNetworkTransport().use { transport ->
                    val job = launch {
                        try {
                            transport.streamSse(NetworkRequest(server.url), onEvent = { seen.complete(Unit) })
                        } catch (failure: CancellationException) {
                            cancelled.complete(true)
                            throw failure
                        }
                    }
                    withTimeout(5000) { seen.await() }
                    withTimeout(5000) { job.cancelAndJoin() }
                    assertTrue(cancelled.await())
                    assertTrue(server.awaitDisconnect())
                }
            }
        }
    }
}

/** Test-only local peer that observes the client side of a live socket closing. */
private class DisconnectServer(private val sendHeaders: Boolean) : AutoCloseable {
    private val listener = ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
    private val socket = AtomicReference<Socket?>()
    private val request = CountDownLatch(1)
    private val disconnected = CountDownLatch(1)
    private val failure = AtomicReference<Throwable?>()
    val url = "http://127.0.0.1:${listener.localPort}/stream"
    private val worker = thread(name = "s1-07-test-server") {
        try {
            listener.accept().use { peer ->
                socket.set(peer)
                peer.soTimeout = 6000
                val input = peer.getInputStream()
                var tail = ""
                while (!tail.endsWith("\r\n\r\n")) {
                    val byte = input.read()
                    if (byte < 0) return@use
                    tail = (tail + byte.toChar()).takeLast(4)
                }
                if (sendHeaders) {
                    peer.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\n" +
                        "Transfer-Encoding: chunked\r\n\r\n9\r\ndata: x\n\n\r\n").toByteArray())
                    peer.getOutputStream().flush()
                }
                request.countDown()
                if (input.read() == -1) disconnected.countDown()
            }
        } catch (caught: Exception) {
            failure.set(caught)
        }
    }

    suspend fun awaitRequest(): Boolean = await(request)
    suspend fun awaitDisconnect(): Boolean = await(disconnected)

    private suspend fun await(latch: CountDownLatch): Boolean = withContext(Dispatchers.IO) {
        val observed = latch.await(5, TimeUnit.SECONDS)
        failure.get()?.let { throw AssertionError("test server failed", it) }
        observed
    }

    override fun close() {
        socket.get()?.close()
        listener.close()
        worker.join(7000)
        check(!worker.isAlive) { "test server did not stop" }
    }
}

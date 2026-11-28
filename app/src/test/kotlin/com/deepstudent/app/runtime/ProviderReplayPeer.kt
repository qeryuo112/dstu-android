package com.deepstudent.app.runtime

import com.deepstudent.platform.network.HttpLimits
import com.deepstudent.platform.network.HttpResponse
import com.deepstudent.platform.network.NetworkRequest
import com.deepstudent.platform.network.NetworkTransport
import com.deepstudent.platform.network.ResponseHead
import com.deepstudent.platform.network.SseEnd
import com.deepstudent.platform.network.SseEvent
import com.deepstudent.platform.network.SseLimits
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Rewrite only the transport destination. A changed provider URL fails before opening any socket. */
internal class ProviderReplayNetwork(
    private val expectedUrl: String,
    private val peer: ProviderReplayPeer,
    private val delegate: NetworkTransport,
) : NetworkTransport {
    private val count = AtomicInteger()
    val requests: Int get() = count.get()

    override suspend fun execute(request: NetworkRequest, limits: HttpLimits): HttpResponse = delegate.execute(toLoopback(request), limits)

    override suspend fun streamSse(
        request: NetworkRequest,
        limits: SseLimits,
        onResponse: suspend (ResponseHead) -> Unit,
        onEvent: suspend (SseEvent) -> Unit,
    ): SseEnd = delegate.streamSse(toLoopback(request), limits, onResponse, onEvent)

    private fun toLoopback(request: NetworkRequest): NetworkRequest {
        check(expectedUrl == request.url) { "provider URL differs from the frozen route" }
        count.incrementAndGet()
        return NetworkRequest(peer.origin + URI(request.url).rawPath, request.method, request.headers, request.body)
    }

    override fun close() = delegate.close()
}

internal class ProviderReplayReply(val status: Int, val contentType: String, val body: String, val keepOpen: Boolean = false) {
    companion object {
        fun sse(frames: List<String>, keepOpen: Boolean = false) = ProviderReplayReply(
            200,
            "text/event-stream",
            frames.joinToString(separator = "") { "data: $it\n\n" },
            keepOpen,
        )

        fun rejection(status: Int) = ProviderReplayReply(status, "application/json", """{"error":"$REPLAY_PRIVATE_DETAIL"}""")
    }
}

internal const val REPLAY_PRIVATE_DETAIL = "synthetic-private-upstream-detail"

internal class ProviderReplayCapture(val method: String, val path: String, val headers: Map<String, String>, val body: String)

/** Single-request peer; in hold-open mode it only stops after observing the client close the socket. */
internal class ProviderReplayPeer(private val reply: ProviderReplayReply) : AutoCloseable {
    private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    private val active = AtomicReference<Socket?>()
    private val closed = AtomicBoolean()
    private val capture = CompletableFuture<ProviderReplayCapture>()
    private val disconnect = CompletableFuture<Boolean>()
    val origin = "http://127.0.0.1:${server.localPort}"
    private val worker = thread(name = "s3-04-provider-replay", isDaemon = true) { serve() }

    suspend fun captured(): ProviderReplayCapture = capture.bounded()

    suspend fun disconnected(): Boolean = disconnect.bounded()

    private fun serve() {
        try {
            server.accept().use { socket ->
                active.set(socket)
                if (closed.get()) return
                socket.soTimeout = 6_000
                val input = socket.getInputStream().buffered()
                capture.complete(input.readRequest())
                socket.sendReply(reply)
                if (reply.keepOpen) disconnect.complete(input.read() == -1)
            }
        } catch (failure: Exception) {
            capture.completeExceptionally(failure)
            disconnect.completeExceptionally(failure)
        }
    }

    override fun close() {
        closed.set(true)
        server.close()
        active.get()?.close()
        worker.join(7_000)
        check(!worker.isAlive) { "test peer did not stop" }
    }
}

private fun BufferedInputStream.readRequest(): ProviderReplayCapture {
    val start = nextLine().split(' ')
    check(start.size == 3)
    val headers = linkedMapOf<String, String>()
    repeat(100) {
        val header = nextLine()
        if (header.isEmpty()) {
            val n = headers.getValue("content-length").toInt()
            check(n in 1..262_144)
            val body = readNBytes(n)
            check(body.size == n)
            return ProviderReplayCapture(start[0], start[1], headers, body.decodeToString())
        }
        val separator = header.indexOf(':')
        check(separator > 0)
        val name = header.substring(0, separator).lowercase(Locale.ROOT)
        check(headers.put(name, header.substring(separator + 1).trim()) == null) { "duplicate HTTP header: $name" }
    }
    error("too many HTTP headers")
}

private fun BufferedInputStream.nextLine(): String {
    val bytes = ByteArrayOutputStream()
    repeat(16_384) {
        val next = read()
        check(next >= 0) { "unexpected EOF in HTTP headers" }
        if (next == '\n'.code) return bytes.toString(Charsets.US_ASCII.name()).removeSuffix("\r")
        bytes.write(next)
    }
    error("HTTP line exceeds test budget")
}

private fun Socket.sendReply(reply: ProviderReplayReply) {
    val output = getOutputStream()
    val bytes = reply.body.toByteArray(Charsets.UTF_8)
    val transfer = if (reply.keepOpen) "Transfer-Encoding: chunked" else "Content-Length: ${bytes.size}\r\nConnection: close"
    output.write("HTTP/1.1 ${reply.status} Replay\r\nContent-Type: ${reply.contentType}\r\n$transfer\r\n\r\n".toByteArray())
    if (reply.keepOpen) output.write("${bytes.size.toString(16)}\r\n".toByteArray())
    output.write(bytes)
    if (reply.keepOpen) output.write("\r\n".toByteArray())
    output.flush()
}

private suspend fun <T> CompletableFuture<T>.bounded(): T = withContext(Dispatchers.IO) { get(5, TimeUnit.SECONDS) }

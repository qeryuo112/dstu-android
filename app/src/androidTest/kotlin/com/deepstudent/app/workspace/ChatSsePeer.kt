package com.deepstudent.app.workspace

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

internal class ChatSsePeer : AutoCloseable {
    private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    private val completion = CountDownLatch(1)
    private val client = AtomicReference<Socket?>()
    private val failure = AtomicReference<Throwable?>()
    @Volatile private var closing = false
    val url = "http://127.0.0.1:${server.localPort}/chat"
    private val worker = thread(name = "s117-chat-peer") {
        try {
            server.accept().use { socket ->
                client.set(socket)
                socket.soTimeout = 10_000
                val reader = socket.getInputStream().bufferedReader()
                var length = 0
                while (true) {
                    val line = reader.readLine() ?: error("missing request")
                    if (line.isEmpty()) break
                    if (line.startsWith("Content-Length:", true)) length = line.substringAfter(':').trim().toInt()
                }
                repeat(length) { check(reader.read() >= 0) }
                val output = socket.getOutputStream()
                output.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n".toByteArray())
                output.write("data: {\"delta\":\"中文\"}\n\n".toByteArray())
                output.flush()
                check(completion.await(15, TimeUnit.SECONDS))
                output.write("data: {\"delta\":\"👩‍💻\"}\n\ndata: [DONE]\n\n".toByteArray())
                output.flush()
            }
        } catch (caught: Throwable) { if (!closing) failure.set(caught) }
    }

    fun finish() { completion.countDown() }
    override fun close() {
        closing = true
        completion.countDown()
        client.get()?.close()
        server.close()
        worker.join(5000)
        check(!worker.isAlive)
        failure.get()?.let { throw AssertionError("chat peer failed", it) }
    }
}

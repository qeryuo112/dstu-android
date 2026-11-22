package com.deepstudent.app.runtime

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Instrumentation-only HTTP peer; production uses the normal typed transport. */
internal class StreamFixturePeer : AutoCloseable {
    private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    private val socket = AtomicReference<Socket?>()
    private val finish = AtomicBoolean()
    private val failure = AtomicReference<Exception?>()
    val url = "http://127.0.0.1:${server.localPort}/fixture"
    private val worker = thread(name = "fgs-stream-fixture") {
        try {
            server.accept().use { peer ->
                socket.set(peer)
                peer.soTimeout = 10000
                val input = peer.getInputStream()
                var tail = ""
                while (!tail.endsWith("\r\n\r\n")) {
                    val next = input.read()
                    check(next >= 0) { "request incomplete" }
                    tail = (tail + next.toChar()).takeLast(4)
                }
                val output = peer.getOutputStream()
                output.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\n\r\n".toByteArray())
                var count = 0
                while (!finish.get()) {
                    output.write("data: segment${count++}\n\n".toByteArray())
                    output.flush()
                    Thread.sleep(100)
                }
            }
        } catch (caught: Exception) {
            if (!finish.get()) failure.set(caught)
        }
    }

    fun complete() { finish.set(true) }

    override fun close() {
        finish.set(true)
        socket.get()?.close()
        server.close()
        worker.join(3000)
        check(!worker.isAlive) { "stream fixture did not stop" }
    }
}

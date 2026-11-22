package com.deepstudent.app.workspace

import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import kotlin.concurrent.thread

internal class BrowserPagePeer : AutoCloseable {
    private val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 250 }
    @Volatile private var closed = false
    val url = "http://127.0.0.1:${server.localPort}/workspace"
    private val worker = thread(name = "s117-browser-peer") {
        while (!closed) {
            try {
                server.accept().use { peer ->
                    peer.soTimeout = 3000
                    val reader = peer.getInputStream().bufferedReader()
                    while (!reader.readLine().isNullOrEmpty()) { }
                    val body = "<!doctype html><html><head><title>DSTU workspace</title></head><body><h1>Workspace browser</h1><p>Browser panel ready</p></body></html>".toByteArray()
                    peer.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(body); flush()
                    }
                }
            } catch (_: SocketTimeoutException) { }
            catch (failure: Exception) { if (!closed) throw failure }
        }
    }
    override fun close() { closed = true; server.close(); worker.join(5000); check(!worker.isAlive) }
}

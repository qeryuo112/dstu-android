package com.deepstudent.features.browser.data

import java.net.ServerSocket
import java.net.SocketException
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

internal class LoopbackLoginServer : AutoCloseable {
    private val server = ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"))
    private val worker = Executors.newSingleThreadExecutor()
    val origin = "http://127.0.0.1:${server.localPort}"

    init { worker.submit { serve() } }

    private fun serve() {
        while (!server.isClosed) {
            val socket = try { server.accept() } catch (_: SocketException) { return }
            socket.use { connection ->
                connection.soTimeout = 5000
                val reader = connection.getInputStream().bufferedReader(StandardCharsets.US_ASCII)
                val request = reader.readLine() ?: return@use
                val headers = mutableListOf<String>()
                while (true) { val line = reader.readLine() ?: break; if (line.isEmpty()) break; headers.add(line) }
                val path = request.split(' ').getOrNull(1).orEmpty()
                val authenticated = headers.any { it.startsWith("Cookie:", true) && it.contains("s115=synthetic") }
                val (status, extra, body) = response(path, authenticated)
                val bytes = body.toByteArray(StandardCharsets.UTF_8)
                val header = "HTTP/1.1 $status\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n$extra\r\n"
                connection.getOutputStream().write(header.toByteArray(StandardCharsets.US_ASCII))
                connection.getOutputStream().write(bytes)
            }
        }
    }

    private fun response(path: String, authenticated: Boolean): Triple<String, String, String> = when (path) {
        "/login" -> Triple("302 Found", "Set-Cookie: s115=synthetic; Path=/; HttpOnly; SameSite=Lax\r\nLocation: /account\r\n", "")
        "/account" -> Triple("200 OK", "", if (authenticated) "<title>Signed in</title><a id='next' href='/next'>Next</a>" else "<title>Signed out</title>")
        "/next" -> Triple("200 OK", "", "<title>Next</title>")
        else -> Triple("200 OK", "", "<title>Login</title><a id='login' href='/login'>Sign in</a>")
    }

    override fun close() { server.close(); worker.shutdownNow() }
}

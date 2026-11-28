package com.deepstudent.app.runtime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URISyntaxException
import java.util.concurrent.TimeUnit

/** Scoped authorization callback; not a general browser or web server. */
internal class CodexLoopbackListener private constructor(private val server: ServerSocket) : AutoCloseable {
    val port: Int get() = server.localPort

    suspend fun serve(handle: suspend (String) -> CodexCallbackDisposition) = withContext(Dispatchers.IO) {
        while (!server.isClosed) {
            currentCoroutineContext().ensureActive()
            val client = accept() ?: continue
            client.use {
                val callback = readTarget(client)
                if (callback == null) {
                    reply(client, CodexCallbackDisposition.INVALID)
                } else {
                    val disposition = handle(callback)
                    reply(client, disposition)
                    if (disposition != CodexCallbackDisposition.INVALID) return@withContext
                }
            }
        }
    }

    private fun accept(): Socket? = try {
        server.accept()
    } catch (_: SocketTimeoutException) {
        null
    } catch (error: IOException) {
        if (!server.isClosed) throw error
        null
    }

    private fun readTarget(client: Socket): String? = try {
        client.soTimeout = READ_TIMEOUT_MILLIS
        val input = client.getInputStream().buffered()
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(READ_TIMEOUT_MILLIS.toLong())
        val request = input.readCallbackLine(deadline)?.split(' ')
        val target = request?.takeIf { it.size == 3 && it[0] == "GET" && it[2] in HTTP_VERSIONS }?.get(1)
        if (target != null && input.consumeHeaders(deadline) && validTarget(target)) "http://localhost:$port$target" else null
    } catch (_: IOException) {
        null
    }

    private fun InputStream.consumeHeaders(deadline: Long): Boolean {
        var remaining = MAX_REQUEST_BYTES
        repeat(MAX_HEADER_LINES) {
            val line = readCallbackLine(deadline) ?: return false
            if (line.isEmpty()) return true
            remaining -= line.length + 2
            if (remaining < 0) return false
        }
        return false
    }

    private fun InputStream.readCallbackLine(deadline: Long): String? {
        val value = StringBuilder()
        repeat(MAX_REQUEST_BYTES) {
            if (System.nanoTime() >= deadline) return null
            val next = read()
            if (next == '\r'.code) return if (read() == '\n'.code) value.toString() else null
            if (next !in 0x20..0x7e) return null
            value.append(next.toChar())
        }
        return null
    }

    private fun validTarget(target: String): Boolean {
        if (!target.startsWith("$CALLBACK_PATH?")) return false
        val parsed = try {
            URI(target)
        } catch (_: URISyntaxException) {
            return false
        }
        return !parsed.isAbsolute && parsed.rawAuthority == null && parsed.rawPath == CALLBACK_PATH && parsed.rawFragment == null
    }

    private fun reply(client: Socket, disposition: CodexCallbackDisposition) {
        val success = disposition == CodexCallbackDisposition.COMPLETE
        val status = if (success) "200 OK" else "400 Bad Request"
        val message = if (success) "Authorization complete. You may return to the app." else "Authorization was not completed."
        val bytes = message.encodeToByteArray()
        val head = "HTTP/1.1 $status\r\nContent-Type: text/plain; charset=utf-8\r\nCache-Control: no-store\r\n" +
            "Content-Security-Policy: default-src 'none'\r\nX-Content-Type-Options: nosniff\r\n" +
            "Connection: close\r\nContent-Length: ${bytes.size}\r\n\r\n"
        runCatching {
            client.getOutputStream().apply {
                write(head.encodeToByteArray())
                write(bytes)
                flush()
            }
        }
    }

    override fun close() = server.close()

    companion object {
        const val CALLBACK_PATH = "/auth/callback"
        private const val ACCEPT_POLL_MILLIS = 250
        private const val READ_TIMEOUT_MILLIS = 2_000
        private const val MAX_REQUEST_BYTES = 12 * 1024
        private const val MAX_HEADER_LINES = 48
        private val HTTP_VERSIONS = setOf("HTTP/1.0", "HTTP/1.1")
        private val CODEX_PORTS = listOf(1455, 1457)

        fun open(): CodexLoopbackListener? = CODEX_PORTS.firstNotNullOfOrNull(::bind)

        internal fun bind(port: Int): CodexLoopbackListener? {
            val socket = ServerSocket()
            return try {
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 8)
                socket.soTimeout = ACCEPT_POLL_MILLIS
                CodexLoopbackListener(socket)
            } catch (_: IOException) {
                socket.close()
                null
            }
        }
    }
}

internal enum class CodexCallbackDisposition { COMPLETE, INVALID, TERMINAL_FAILURE }

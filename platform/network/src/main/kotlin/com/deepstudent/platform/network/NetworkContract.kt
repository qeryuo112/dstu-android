package com.deepstudent.platform.network

import java.io.Closeable
import java.util.Collections

class NetworkHeader(val name: String, val value: String) {
    override fun toString(): String = "NetworkHeader(redacted)"
}

class NetworkRequest(
    val url: String,
    val method: String = "GET",
    headers: List<NetworkHeader> = emptyList(),
    body: ByteArray? = null,
) {
    val headers: List<NetworkHeader> = immutableHeaders(headers)
    private val storedBody: ByteArray? = body?.copyOf()
    val body: ByteArray? get() = storedBody?.copyOf()

    override fun toString(): String = "NetworkRequest(redacted)"
}

class ResponseHead(val statusCode: Int, headers: List<NetworkHeader>) {
    val headers: List<NetworkHeader> = immutableHeaders(headers)
    override fun toString(): String = "ResponseHead(statusCode=$statusCode, headers=redacted)"
}

class HttpResponse(val head: ResponseHead, body: ByteArray) {
    private val storedBody = body.copyOf()
    val body: ByteArray get() = storedBody.copyOf()
    override fun toString(): String = "HttpResponse(statusCode=${head.statusCode}, body=redacted)"
}

internal fun immutableHeaders(headers: List<NetworkHeader>): List<NetworkHeader> =
    Collections.unmodifiableList(ArrayList(headers))

class HttpLimits(val maxResponseBytes: Int = 4 * 1024 * 1024) {
    init {
        require(maxResponseBytes in 0..64 * 1024 * 1024) { "invalid HTTP response budget" }
    }
}

interface NetworkTransport : Closeable {
    suspend fun execute(request: NetworkRequest, limits: HttpLimits = HttpLimits()): HttpResponse

    suspend fun streamSse(
        request: NetworkRequest,
        limits: SseLimits = SseLimits(),
        onResponse: suspend (ResponseHead) -> Unit = {},
        onEvent: suspend (SseEvent) -> Unit,
    ): SseEnd
}

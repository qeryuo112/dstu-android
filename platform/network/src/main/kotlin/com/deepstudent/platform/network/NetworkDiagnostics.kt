package com.deepstudent.platform.network

enum class NetworkError(val fixedMessage: String) {
    INVALID_REQUEST("network request is invalid"),
    CLOSED("network transport is closed"),
    CONNECTION("network connection failed"),
    PROXY_AUTHENTICATION("network proxy requires authentication"),
    RESPONSE_TOO_LARGE("HTTP response exceeds its budget"),
    SSE_TOO_LARGE("SSE frame exceeds its budget"),
    SSE_INVALID_UTF8("SSE frame contains invalid UTF-8"),
    SSE_INVALID_CONTENT_TYPE("SSE response has an invalid content type"),
    DECODER_CLOSED("SSE decoder is closed"),
    INTERNAL("network operation failed"),
}

class NetworkException(val error: NetworkError) : Exception(error.fixedMessage)

enum class NetworkOperation { HTTP, SSE }
enum class NetworkPhase { STARTED, COMPLETED, TRUNCATED, REJECTED, CANCELLED, FAILED }

data class NetworkLogEvent(
    val correlationId: String,
    val operation: NetworkOperation,
    val phase: NetworkPhase,
    val elapsedMillis: Long,
    val error: NetworkError? = null,
) {
    val owner: String get() = "platform/network"
}

fun interface NetworkLog {
    fun onEvent(event: NetworkLogEvent)

    companion object {
        val NONE: NetworkLog = NetworkLog { }
    }
}

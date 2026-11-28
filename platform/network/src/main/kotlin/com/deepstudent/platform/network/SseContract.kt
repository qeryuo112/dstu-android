package com.deepstudent.platform.network

class SseLimits(
    val maxLineBytes: Int = 64 * 1024,
    val maxEventBytes: Int = 1024 * 1024,
    /** Optional protocol-owned stop marker; it is delivered before the peer is closed. */
    val stopAfterEvent: ((SseEvent) -> Boolean)? = null,
    /** Opt-in bounded access to a known status whose error code has protocol meaning. */
    val rejectionBody: SseRejectionBody? = null,
) {
    init {
        require(maxLineBytes in 1..1024 * 1024) { "invalid SSE line budget" }
        require(maxEventBytes in 1..4 * 1024 * 1024) { "invalid SSE event budget" }
    }
}

/** [onBody] must inspect synchronously; the buffer is wiped when the callback returns. */
class SseRejectionBody(val statusCode: Int, val limits: HttpLimits, val onBody: (ByteArray) -> Unit) {
    init {
        require(statusCode in 400..599) { "invalid SSE rejection status" }
    }
}

class SseEvent(val data: String, val type: String, val lastEventId: String) {
    override fun toString(): String = "SseEvent(redacted)"
}

enum class SseEnd {
    COMPLETE,
    TRUNCATED,
    REJECTED_HTTP_STATUS,
}

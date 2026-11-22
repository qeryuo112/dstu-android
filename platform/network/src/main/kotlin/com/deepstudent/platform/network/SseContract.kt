package com.deepstudent.platform.network

class SseLimits(
    val maxLineBytes: Int = 64 * 1024,
    val maxEventBytes: Int = 1024 * 1024,
) {
    init {
        require(maxLineBytes in 1..1024 * 1024) { "invalid SSE line budget" }
        require(maxEventBytes in 1..4 * 1024 * 1024) { "invalid SSE event budget" }
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

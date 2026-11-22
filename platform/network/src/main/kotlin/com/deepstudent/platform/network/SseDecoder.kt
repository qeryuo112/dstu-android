package com.deepstudent.platform.network

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** Incremental framing only; no HTTP, retry policy, or provider semantics. */
class SseDecoder(private val limits: SseLimits = SseLimits()) {
    private val line = ByteArrayOutputStream()
    private val data = StringBuilder()
    private var eventType = ""
    private var lastEventId = ""
    private var eventBytes = 0
    private var dataSeen = false
    private var dirty = false
    private var firstLine = true
    private var skipLf = false
    private var closed = false

    /** A call accepts at most 8192 bytes, keeping the returned event list bounded. */
    fun accept(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): List<SseEvent> {
        checkOpen()
        require(offset >= 0 && length in 0..8192 && offset <= bytes.size - length) {
            "invalid SSE input range"
        }
        return try {
            val events = mutableListOf<SseEvent>()
            for (index in offset until offset + length) {
                consume(bytes[index].toInt() and 0xff)?.let(events::add)
            }
            events
        } catch (failure: NetworkException) {
            closed = true
            throw failure
        }
    }

    /** Pending, undispatched fields are discarded at EOF and reported as truncated. */
    fun finish(): SseEnd {
        checkOpen()
        closed = true
        return if (dirty || line.size() != 0) SseEnd.TRUNCATED else SseEnd.COMPLETE
    }

    private fun consume(value: Int): SseEvent? {
        if (skipLf) {
            skipLf = false
            if (value == LF) return null
        }
        return when (value) {
            CR -> {
                skipLf = true
                endLine()
            }
            LF -> endLine()
            else -> {
                countByte()
                if (line.size() >= limits.maxLineBytes) throw NetworkException(NetworkError.SSE_TOO_LARGE)
                line.write(value)
                null
            }
        }
    }

    private fun endLine(): SseEvent? {
        countByte()
        val text = decodeLine()
        if (text.isEmpty()) return dispatch()
        dirty = true
        processField(text)
        return null
    }

    private fun decodeLine(): String {
        val bytes = line.toByteArray()
        line.reset()
        val text = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (failure: CharacterCodingException) {
            throw NetworkException(NetworkError.SSE_INVALID_UTF8)
        }
        val result = if (firstLine) text.removePrefix("\uFEFF") else text
        firstLine = false
        return result
    }

    private fun processField(text: String) {
        if (text.startsWith(':')) return
        val separator = text.indexOf(':')
        val name = if (separator < 0) text else text.substring(0, separator)
        val raw = if (separator < 0) "" else text.substring(separator + 1)
        val value = raw.removePrefix(" ")
        when (name) {
            "data" -> {
                dataSeen = true
                data.append(value).append('\n')
            }
            "event" -> eventType = value
            "id" -> if ('\u0000' !in value) lastEventId = value
        }
    }

    private fun dispatch(): SseEvent? {
        val result = if (dataSeen) {
            SseEvent(data.substring(0, data.length - 1), eventType.ifEmpty { "message" }, lastEventId)
        } else {
            null
        }
        data.setLength(0)
        dataSeen = false
        eventType = ""
        dirty = false
        eventBytes = 0
        return result
    }

    private fun countByte() {
        if (eventBytes >= limits.maxEventBytes) throw NetworkException(NetworkError.SSE_TOO_LARGE)
        eventBytes++
    }

    private fun checkOpen() {
        if (closed) throw NetworkException(NetworkError.DECODER_CLOSED)
    }

    private companion object {
        const val CR = 13
        const val LF = 10
    }
}

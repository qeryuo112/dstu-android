package com.deepstudent.platform.network

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SseDecoderTest {
    @Test
    fun recordingReplaysAtEverySingleSplitAndByteByByte() {
        val bytes = recording()
        for (split in 0..bytes.size) {
            val decoder = SseDecoder()
            val events = decoder.accept(bytes, 0, split) + decoder.accept(bytes, split, bytes.size - split)
            assertRecording(events, decoder.finish())
        }
        val decoder = SseDecoder()
        val events = bytes.flatMap { decoder.accept(byteArrayOf(it)) }
        assertRecording(events, decoder.finish())
    }

    @Test
    fun lineEndingsBomIdsEmptyDataAndUnknownFieldsFollowFramingRules() {
        val input = "\uFEFFid: first\rdata: one\r\nevent: custom\nunknown: ignore\n\n" +
            "id: bad\u0000id\ndata\n\ndata: two\r\rdata: final\n\n"
        val decoder = SseDecoder()
        val events = decoder.accept(input.toByteArray(Charsets.UTF_8))
        assertEquals(listOf("one", "", "two", "final"), events.map { it.data })
        assertEquals(listOf("custom", "message", "message", "message"), events.map { it.type })
        assertTrue(events.all { it.lastEventId == "first" })
        assertEquals(SseEnd.COMPLETE, decoder.finish())
    }

    @Test
    fun noBlankLineAtEofDoesNotDispatchPartialData() {
        for (input in listOf("data: a", "data: a\n", "data: a\r\n")) {
            val decoder = SseDecoder()
            assertTrue(decoder.accept(input.toByteArray()).isEmpty())
            assertEquals(SseEnd.TRUNCATED, decoder.finish())
        }
        val decoder = SseDecoder()
        assertTrue(decoder.accept("data: a\n\n".toByteArray()).size == 1)
        assertEquals(SseEnd.COMPLETE, decoder.finish())
        assertError(NetworkError.DECODER_CLOSED) { decoder.accept(byteArrayOf()) }
    }

    @Test
    fun budgetsAreAppliedBeforeBuffersGrowAndDecoderFailsClosed() {
        val line = SseDecoder(SseLimits(maxLineBytes = 5, maxEventBytes = 50))
        assertError(NetworkError.SSE_TOO_LARGE) { line.accept("123456".toByteArray()) }
        assertError(NetworkError.DECODER_CLOSED) { line.finish() }
        val event = SseDecoder(SseLimits(maxLineBytes = 10, maxEventBytes = 12))
        assertError(NetworkError.SSE_TOO_LARGE) { event.accept("data: a\ndata: b\n\n".toByteArray()) }
        val exact = SseDecoder(SseLimits(maxLineBytes = 7, maxEventBytes = 9))
        assertEquals("a", exact.accept("data: a\n\n".toByteArray()).single().data)
        assertEquals(SseEnd.COMPLETE, exact.finish())
    }

    @Test
    fun invalidUtf8AndIncompleteCodePointAreNeverReplacedSilently() {
        val invalid = SseDecoder()
        assertError(NetworkError.SSE_INVALID_UTF8) { invalid.accept(byteArrayOf(0xc3.toByte(), 0x28, 10)) }
        val unfinished = SseDecoder()
        assertTrue(unfinished.accept(byteArrayOf(0xe4.toByte(), 0xb8.toByte())).isEmpty())
        assertEquals(SseEnd.TRUNCATED, unfinished.finish())
    }

    @Test
    fun valuesAndErrorsDoNotExposeTheirPayloadInToStringOrCause() {
        val secret = "S1-07-secret-sentinel"
        assertFalse(SseEvent(secret, secret, secret).toString().contains(secret))
        val error = NetworkException(NetworkError.SSE_TOO_LARGE)
        assertNull(error.cause)
        assertFalse(error.toString().contains(secret))
    }

    private fun recording(): ByteArray {
        val text = javaClass.getResourceAsStream("/s1-07/sse-recording.hex")!!.bufferedReader().use { it.readText() }
        val hex = text.trim()
        val output = ByteArrayOutputStream()
        hex.chunked(2).forEach { output.write(it.toInt(16)) }
        return output.toByteArray()
    }

    private fun assertRecording(events: List<SseEvent>, end: SseEnd) {
        assertEquals(1, events.size)
        assertEquals("中文\nline two", events.single().data)
        assertEquals("update", events.single().type)
        assertEquals("42", events.single().lastEventId)
        assertEquals(SseEnd.TRUNCATED, end)
    }
}

internal fun assertError(error: NetworkError, block: () -> Unit) {
    try {
        block()
        fail("expected a network failure")
    } catch (failure: NetworkException) {
        assertEquals(error, failure.error)
        assertNull(failure.cause)
    }
}

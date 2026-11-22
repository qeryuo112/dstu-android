package com.deepstudent.platform.security

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DsbkPreflightContractTest {
    private val events = mutableListOf<SecurityAuditEvent>()
    private val deriver = PreflightCountingDeriver()
    private val engine = DsbkEngine(deriver)
    private val crypto = DsbkCrypto(engine, SecurityAudit { events.add(it) })
    private val password = "not-logged-password".toCharArray()
    private val params = DsbkKdfParams(32, 1, 1)

    @Test
    fun knownV1AndV2LengthsExposeOnlyUnauthenticatedMetadataAndDoNotDerive() {
        val plain = "private-payload".toByteArray()
        val v1 = engine.encryptV1(plain, password, ByteArray(16), params, ByteArray(12))
        val v2 = ByteArrayOutputStream().apply {
            engine.encryptV2(ByteArrayInputStream(plain), this, password, ByteArray(16), params, ByteArray(7))
        }.toByteArray()
        deriver.calls = 0
        for ((expected, bytes) in listOf(1 to v1, 2 to v2)) {
            val stream = ByteArrayInputStream(bytes)
            val result = crypto.preflight(stream, bytes.size.toLong())
            assertEquals(expected, result.version)
            assertEquals(plain.size.toLong(), result.plaintextBytes)
            assertEquals(bytes.size.toLong() - if (expected == 1) 45 else 44, stream.available().toLong())
            assertEquals(params, result.kdf)
        }
        assertEquals(0, deriver.calls)
        val unknown = crypto.preflight(ByteArrayInputStream(v2))
        assertNull(unknown.plaintextBytes)
        assertEquals(3, events.count { it.operation == SecurityOperation.DSBK_PREFLIGHT && it.outcome == SecurityOutcome.SUCCEEDED })
    }

    @Test
    fun malformedHeadersAndKnownLengthsRejectBeforeBodyOrKeyDerivation() {
        val v1 = DsbkHeaderCodec.writeV1(params, ByteArray(16), ByteArray(12))
        val v2 = DsbkHeaderCodec.writeV2(params, ByteArray(16), ByteArray(7))
        val hugeV1 = DsbkFormat.V1_HEADER_LEN + DsbkFormat.GCM_TAG_LEN + DsbkCrypto.MAX_IN_MEMORY_BYTES + 1
        assertEquals(DsbkError.V1_CONVERSION_REQUIRED, error { crypto.preflight(ByteArrayInputStream(v1), hugeV1) })
        assertEquals(DsbkError.DATA_MISSING, error { crypto.preflight(ByteArrayInputStream(v1), 45) })
        assertEquals(DsbkError.AUTHENTICATION_FAILED, error { crypto.preflight(ByteArrayInputStream(v1), 46) })
        assertEquals(DsbkError.INPUT_LIMIT_EXCEEDED, error { crypto.preflight(ByteArrayInputStream(v1), 100, maxInputBytes = 99) })
        assertEquals(DsbkError.OUTPUT_LIMIT_EXCEEDED, error { crypto.preflight(ByteArrayInputStream(v1), 62, maxOutputBytes = 0) })
        val badChunk = v2.copyOf().apply { ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN).putInt(40, 0) }
        assertEquals(DsbkError.CHUNK_INVALID, error { crypto.preflight(ByteArrayInputStream(badChunk)) })
        val badKdf = v1.copyOf().apply { ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN).putInt(5, Int.MAX_VALUE) }
        assertEquals(DsbkError.KDF_BUDGET_EXCEEDED, error { crypto.preflight(ByteArrayInputStream(badKdf)) })
        assertEquals(DsbkError.VERSION_UNSUPPORTED, error { crypto.preflight(ByteArrayInputStream(v1.copyOf().apply { this[4] = 3 })) })
        assertEquals(0, deriver.calls)
        assertTrue(events.filter { it.operation == SecurityOperation.DSBK_PREFLIGHT }.all { it.correlationId != null && it.elapsedMs != null && it.phase == SecurityPhase.HEADER })
    }

    @Test
    fun v2KnownLengthChecksFrameShapeAndOutputBudget() {
        val v2 = DsbkHeaderCodec.writeV2(params, ByteArray(16), ByteArray(7))
        val width = DsbkFormat.STREAM_CHUNK.toLong() + DsbkFormat.GCM_TAG_LEN
        val full = DsbkFormat.V2_HEADER_LEN + 2 * width
        assertEquals(2L * DsbkFormat.STREAM_CHUNK, crypto.preflight(ByteArrayInputStream(v2), full).plaintextBytes)
        assertEquals(DsbkError.AUTHENTICATION_FAILED, error { crypto.preflight(ByteArrayInputStream(v2), full + 1) })
        assertEquals(DsbkError.OUTPUT_LIMIT_EXCEEDED, error { crypto.preflight(ByteArrayInputStream(v2), full, maxOutputBytes = 1) })
    }

    @Test
    fun versionedDecryptAuditUsesOneIdAndNeverLogsSecrets() {
        val plain = "private-payload".toByteArray()
        val bytes = engine.encryptV1(plain, password, ByteArray(16), params, ByteArray(12))
        val sink = ByteArrayDsbkSink(64)
        crypto.decrypt(ByteArrayInputStream(bytes), password, sink, 64)
        val events = events.filter { it.operation == SecurityOperation.DSBK_DECRYPT }
        assertEquals(listOf(SecurityPhase.DISPATCH, SecurityPhase.V1_DECRYPT, SecurityPhase.COMMIT, SecurityPhase.COMMIT), events.map { it.phase })
        assertEquals(1, events.map { it.correlationId }.distinct().size)
        assertTrue(events.all { it.elapsedMs != null })
        val text = events.joinToString("\n")
        assertFalse(text.contains("private-payload"))
        assertFalse(text.contains("not-logged-password"))
        assertFalse(text.contains("nonce"))
    }

    @Test
    fun emptyOrTruncatedV2NeverStartsKdfAndReportsTheVersionPhase() {
        val v2 = DsbkHeaderCodec.writeV2(params, ByteArray(16), ByteArray(7))
        for ((body, expected) in listOf(ByteArray(0) to DsbkError.DATA_MISSING, ByteArray(15) to DsbkError.AUTHENTICATION_FAILED)) {
            val sink = ByteArrayDsbkSink(100)
            val error = error { crypto.decrypt(ByteArrayInputStream(v2 + body), password, sink, 100) }
            assertEquals(expected, error)
            assertEquals(0, sink.stagedBytes)
        }
        assertEquals(0, deriver.calls)
        val failed = events.filter { it.operation == SecurityOperation.DSBK_DECRYPT && it.outcome == SecurityOutcome.FAILED }
        assertEquals(2, failed.size)
        assertTrue(failed.all { it.phase == SecurityPhase.V2_DECRYPT && it.errorCode != null && it.correlationId != null })
    }

    @Test
    fun v1UnknownStreamStopsAtOutputBudgetWithoutStartingKdf() {
        val v1 = DsbkHeaderCodec.writeV1(params, ByteArray(16), ByteArray(12))
        val sink = ByteArrayDsbkSink(8)
        assertEquals(DsbkError.OUTPUT_LIMIT_EXCEEDED,
            error { crypto.decrypt(ByteArrayInputStream(v1 + ByteArray(25)), password, sink, 8) })
        assertEquals(0, deriver.calls)
        assertEquals(0, sink.stagedBytes)
    }

    @Test
    fun heapReservationIsCheckedBeforeAllocation() {
        val baseOnly = 32L * 1024 * 1024 + params.memoryKiB * 1152
        DsbkMemoryBudget.requireAvailable(params, availableBytes = baseOnly + 1024, reserveBytes = 1024)
        assertEquals(DsbkError.MEMORY_BUDGET_EXCEEDED,
            error { DsbkMemoryBudget.requireAvailable(params, availableBytes = baseOnly + 1023, reserveBytes = 1024) })
        assertEquals(DsbkError.KDF_BUDGET_EXCEEDED,
            error { DsbkMemoryBudget.requireAvailable(params, availableBytes = baseOnly - 1) })
        assertTrue(DsbkMemoryBudget.availableHeapBytes() <= Runtime.getRuntime().maxMemory())
    }

    private fun error(block: () -> Any): DsbkError = assertThrows(DsbkException::class.java) { block() }.error
}

private class PreflightCountingDeriver : DsbkKeyDeriver {
    var calls = 0
    override fun derive(password: CharArray, salt: ByteArray, params: DsbkKdfParams): ByteArray {
        calls++
        return ByteArray(DsbkFormat.KEY_LEN) { 5 }
    }
}

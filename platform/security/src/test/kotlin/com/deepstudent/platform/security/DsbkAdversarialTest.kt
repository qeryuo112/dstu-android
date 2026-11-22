package com.deepstudent.platform.security

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DsbkAdversarialTest {
    @get:Rule val temporary = TemporaryFolder()
    private val engine = DsbkEngine(Argon2idKeyDeriver())
    private val password = "test-only-adversarial".toCharArray()

    @Test
    fun finalFlagRejectsWholeTailDeletionDuplicationReorderAppendAndEveryShortTail() {
        val bytes = encrypt(ByteArray(2 * DsbkFormat.STREAM_CHUNK + 33) { it.toByte() })
        val header = bytes.copyOfRange(0, DsbkFormat.V2_HEADER_LEN)
        val width = DsbkFormat.STREAM_CHUNK + DsbkFormat.GCM_TAG_LEN
        val first = bytes.copyOfRange(header.size, header.size + width)
        val second = bytes.copyOfRange(header.size + width, header.size + 2 * width)
        val last = bytes.copyOfRange(header.size + 2 * width, bytes.size)
        val attacks = listOf(
            header + first, // A non-final block cannot become final at EOF.
            header + first + second,
            header + first + first + last,
            header + second + first + last,
            header + first + last,
            bytes + last,
            bytes + byteArrayOf(0),
        ) + (1..DsbkFormat.GCM_TAG_LEN).map { bytes.copyOf(bytes.size - it) }
        for (attack in attacks) reject(attack, DsbkError.AUTHENTICATION_FAILED)
    }

    @Test
    fun zeroBulkReadsAndOneByteFragmentsRoundTripWithoutSpinning() {
        val plain = ByteArray(12345) { it.toByte() }
        val bytes = encrypt(plain)
        val strange = object : InputStream() {
            val source = ByteArrayInputStream(bytes)
            var calls = 0
            override fun read(): Int = source.read()
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                return if (++calls % 2 == 0) source.read(buffer, offset, minOf(7, length)) else 0
            }
        }
        val result = DsbkCrypto.production().decryptToBytes(strange, password, plain.size.toLong())
        assertArrayEquals(plain, result)
    }

    @Test
    fun negativeAndShortInputBudgetsAbortPreexistingStagingAndPreserveTarget() {
        val bytes = encrypt(byteArrayOf(1, 2, 3))
        reject(bytes, DsbkError.OUTPUT_LIMIT_EXCEEDED, outputLimit = -1)
        reject(bytes, DsbkError.INPUT_LIMIT_EXCEEDED, inputLimit = -1)
        reject(bytes, DsbkError.INPUT_LIMIT_EXCEEDED, inputLimit = bytes.size - 1L)
        reject(bytes.copyOf(4), DsbkError.HEADER_INVALID)
        reject(bytes.copyOf(5), DsbkError.HEADER_INVALID)
    }

    @Test
    fun tinyOutputBudgetBoundsReadsEvenWithMaximumLegalChunkHeader() {
        val header = DsbkHeaderCodec.writeV2(TestOnlyKdf.PARAMS, ByteArray(16), ByteArray(7))
        ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(DsbkFormat.V2_CHUNK_OFFSET, DsbkFormat.MAX_STREAM_CHUNK)
        var readBytes = 0
        val source = object : InputStream() {
            override fun read(): Int {
                val offset = readBytes++
                return if (offset < header.size) header[offset].toInt() and 0xff else 0
            }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                repeat(length) { buffer[offset + it] = read().toByte() }
                return length
            }
        }
        val sink = ByteArrayDsbkSink(1)
        val error = assertThrows(DsbkException::class.java) { engine.decrypt(source, password, sink, 1) }
        assertEquals(DsbkError.OUTPUT_LIMIT_EXCEEDED, error.error)
        assertTrue(readBytes <= header.size + 18)
        assertEquals(0, sink.stagedBytes)
    }

    @Test
    fun v1RejectsInvalidParametersBeforeReadingAnUnboundedBody() {
        val params = DsbkKdfParams(DsbkFormat.MAX_MEMORY_KIB + 1L, 1, 1)
        val header = DsbkHeaderCodec.writeV1(params, ByteArray(16), ByteArray(12))
        val input = object : ByteArrayInputStream(header) {
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                if (available() == 0) error("body must not be read for rejected KDF")
                return super.read(bytes, offset, length)
            }
        }
        val error = assertThrows(DsbkException::class.java) {
            engine.decrypt(input, password, ByteArrayDsbkSink(1), Long.MAX_VALUE)
        }
        assertEquals(DsbkError.KDF_BUDGET_EXCEEDED, error.error)
    }

    @Test
    fun heapAdmissionIsExplicitAndDoesNotChangeFrozenProtocolLimits() {
        val error = assertThrows(DsbkException::class.java) {
            DsbkMemoryBudget.requireAvailable(DsbkKdfParams.PRODUCTION, 1024)
        }
        assertEquals(DsbkError.KDF_BUDGET_EXCEEDED, error.error)
        DsbkMemoryBudget.requireAvailable(DsbkKdfParams(DsbkFormat.MAX_MEMORY_KIB, 16, 8), Long.MAX_VALUE)
        assertEquals(DsbkFormat.MAX_MEMORY_KIB, DsbkCrypto.PRODUCTION_MAX_MEMORY_KIB)
        assertEquals(DsbkFormat.MAX_STREAM_CHUNK, DsbkCrypto.PRODUCTION_MAX_CHUNK_BYTES)
    }

    @Test
    fun rejectedSecretOwnershipErasesBufferAndMagicCannotBeMutated() {
        val oversized = ByteArray(SecretBytes.MAX_SIZE_BYTES + 1) { 9 }
        assertThrows(IllegalArgumentException::class.java) { SecretBytes.takeOwnership(oversized) }
        assertTrue(oversized.all { it == 0.toByte() })
        assertThrows(IllegalArgumentException::class.java) {
            SecretBytes.fromUtf8(CharArray(SecretBytes.MAX_SIZE_BYTES) { '中' })
        }
        DsbkFormat.MAGIC.fill(0)
        assertTrue(DsbkFormat.hasMagic("DSBK".toByteArray(), 0))
    }

    @Test
    fun encryptionInputBudgetAndThrownProviderTextUseFixedFailures() {
        val output = ByteArrayOutputStream()
        val limit = assertThrows(DsbkException::class.java) {
            engine.encryptV2(ByteArrayInputStream(ByteArray(3)), output, password,
                ByteArray(16), TestOnlyKdf.PARAMS, ByteArray(7), 2)
        }
        assertEquals(DsbkError.INPUT_LIMIT_EXCEEDED, limit.error)
        val hostile = object : InputStream() { override fun read(): Int = throw java.io.IOException("password=sentinel") }
        val error = assertThrows(DsbkException::class.java) {
            engine.decrypt(hostile, password, ByteArrayDsbkSink(1024), 1024)
        }
        assertFalse(error.toString().contains("sentinel"))
        assertEquals(null, error.cause)
    }

    private fun encrypt(plain: ByteArray): ByteArray = ByteArrayOutputStream().also {
        engine.encryptV2(ByteArrayInputStream(plain), it, password, ByteArray(16), TestOnlyKdf.PARAMS, ByteArray(7))
    }.toByteArray()

    private fun reject(
        bytes: ByteArray,
        expected: DsbkError,
        inputLimit: Long = DsbkCrypto.DEFAULT_MAX_CONTAINER_BYTES,
        outputLimit: Long = 4L * DsbkFormat.STREAM_CHUNK,
    ) {
        val target = File(temporary.root, "active.bin").apply { writeText("keep-active") }
        DsbkStagingArea(File(temporary.root, "parts"), 4L * DsbkFormat.STREAM_CHUNK).use { area ->
            val sink = area.createSink(target)
            sink.write("provisional-secret".toByteArray())
            val error = assertThrows(DsbkException::class.java) {
                engine.decrypt(ByteArrayInputStream(bytes), password, sink, outputLimit, inputLimit)
            }
            assertEquals(expected, error.error)
            assertEquals("keep-active", target.readText())
            assertEquals(0, sink.stagedBytes)
            assertEquals(0, area.cleanupOrphans())
            assertTrue(File(temporary.root, "parts").listFiles()!!.none { it.name.endsWith(".part") })
        }
    }
}

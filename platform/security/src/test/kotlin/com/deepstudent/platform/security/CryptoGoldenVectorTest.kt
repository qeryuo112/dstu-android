package com.deepstudent.platform.security

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Test-only Argon2id parameters copied from the frozen desktop fixture. They
 * must never be used as the production new-encryption default.
 */
internal object TestOnlyKdf {
    val PARAMS = DsbkKdfParams(8L, 1L, 1L)
}

class CryptoGoldenVectorTest {
    private val artifact: JsonObject = Json.parseToJsonElement(
        File(repositoryRoot(), "config/baseline/crypto-golden.json").readText(),
    ).jsonObject

    @Test
    fun frozenDesktopVectorsMatchByteForByte() {
        val engine = DsbkEngine(Argon2idKeyDeriver(), FixedRandomBytes(0xaa))
        val salt = hex(string(artifact["source"]!!.jsonObject, "saltHex"))
        val v1Nonce = hex(string(artifact["source"]!!.jsonObject, "v1NonceHex"))
        val v2Prefix = hex(string(artifact["source"]!!.jsonObject, "v2PrefixHex"))

        for (id in listOf("empty", "unicode", "exact-1MiB", "plus-1")) {
            val record = case(id)
            val plaintext = plaintextFor(record)
            val v1 = engine.encryptV1(plaintext, password(), salt, TestOnlyKdf.PARAMS, v1Nonce)
            val v2 = encryptV2(engine, plaintext, salt, v2Prefix)

            assertRecord(record["v1"]!!.jsonObject, v1)
            assertRecord(record["v2"]!!.jsonObject, v2)
            assertDecrypts(engine, v1, plaintext, plaintext.size.toLong())
            assertDecrypts(engine, v2, plaintext, plaintext.size.toLong())
            assertProductionDecrypts(v1, plaintext)
            assertProductionDecrypts(v2, plaintext)
        }
    }

    @Test
    fun wrongPasswordTamperAndTruncationDoNotActivateStaging() {
        val engine = DsbkEngine(Argon2idKeyDeriver(), FixedRandomBytes(0xaa))
        val source = case("unicode")["v2"]!!.jsonObject
        val v2 = hex(string(source, "fullHex"))
        val max = 1024L * 1024L

        assertRejected(engine, v2, "wrong".toCharArray(), max, DsbkError.AUTHENTICATION_FAILED)
        assertRejected(engine, flipLast(v2), password(), max, DsbkError.AUTHENTICATION_FAILED)
        assertRejected(engine, v2.copyOf(v2.size - 1), password(), max, DsbkError.AUTHENTICATION_FAILED)
        assertRejected(engine, flipFirst(v2), password(), max, DsbkError.HEADER_INVALID)
        assertRejected(engine, withVersion(v2, 3), password(), max, DsbkError.VERSION_UNSUPPORTED)
        assertRejected(engine, withChunk(v2, 0), password(), max, DsbkError.CHUNK_INVALID)
        assertRejected(
            engine,
            withChunk(v2, DsbkFormat.MAX_STREAM_CHUNK + 1),
            password(),
            max,
            DsbkError.CHUNK_INVALID,
        )

        val v1Source = case("unicode")["v1"]!!.jsonObject
        val v1 = hex(string(v1Source, "fullHex"))
        assertRejected(engine, v1, "wrong".toCharArray(), max, DsbkError.AUTHENTICATION_FAILED)
        assertRejected(engine, flipLast(v1), password(), max, DsbkError.AUTHENTICATION_FAILED)
        assertRejected(engine, v1.copyOf(v1.size - 1), password(), max, DsbkError.AUTHENTICATION_FAILED)
        assertRejected(engine, flipFirst(v1), password(), max, DsbkError.HEADER_INVALID)
        assertRejected(engine, withVersion(v1, 3), password(), max, DsbkError.VERSION_UNSUPPORTED)

        val swapped = swapFirstTwoChunks(threeChunkVector(engine))
        assertRejected(engine, swapped, password(), 3L * 1024L * 1024L, DsbkError.AUTHENTICATION_FAILED)
    }

    @Test
    fun kdfBudgetIsRejectedBeforeDerivationAndHeaderIsBound() {
        val recording = RecordingDeriver()
        val engine = DsbkEngine(recording, FixedRandomBytes(0xaa))
        val salt = ByteArray(16) { 7 }
        val prefix = ByteArray(7) { 0xaa.toByte() }
        for (params in listOf(
            DsbkKdfParams(DsbkFormat.MAX_MEMORY_KIB + 1, 1, 1),
            DsbkKdfParams(8, DsbkFormat.MAX_ITERATIONS + 1, 1),
            DsbkKdfParams(8, 1, DsbkFormat.MAX_PARALLELISM + 1),
        )) {
            val header = DsbkHeaderCodec.writeV2(params, salt, prefix)
            val callsBefore = recording.calls.size
            assertRejected(engine, header, password(), 1024, DsbkError.KDF_BUDGET_EXCEEDED)
            assertEquals(callsBefore, recording.calls.size)
        }

        val source = case("unicode")["v2"]!!.jsonObject
        val tampered = hex(string(source, "fullHex"))
        tampered.fill(0x08, 17, 33)
        putUInt32LE(tampered, 5, 9)
        assertRejected(engine, tampered, password(), 1024, DsbkError.AUTHENTICATION_FAILED)
        assertEquals(1, recording.calls.size)
        assertEquals("08".repeat(16), recording.calls.single().saltHex)
        assertEquals(9L, recording.calls.single().memoryKiB)

        val mobileEngine = DsbkEngine(
            recording,
            FixedRandomBytes(0xaa),
            maxMemoryKiB = DsbkCrypto.PRODUCTION_MAX_MEMORY_KIB,
        )
        val mobileHeader = DsbkHeaderCodec.writeV2(
            DsbkKdfParams(DsbkCrypto.PRODUCTION_MAX_MEMORY_KIB + 1L, 1, 1),
            salt,
            prefix,
        )
        val callsBeforeMobile = recording.calls.size
        assertRejected(mobileEngine, mobileHeader, password(), 1024, DsbkError.KDF_BUDGET_EXCEEDED)
        assertEquals(callsBeforeMobile, recording.calls.size)
    }

    @Test
    fun failedAuthenticationClearsStagingAndNeverCommits() {
        val engine = DsbkEngine(Argon2idKeyDeriver(), FixedRandomBytes(0xaa))
        val v2 = multiChunkVector(engine)
        val sink = CollectingSink(1024L * 1024L * 2)
        val failure = expectDsbk {
            engine.decrypt(ByteArrayInputStream(flipLast(v2)), password(), sink, sink.limit)
        }
        assertEquals(DsbkError.AUTHENTICATION_FAILED, failure.error)
        assertTrue(sink.aborted)
        assertFalse(sink.committed)
        assertEquals(0, sink.stagedBytes)
        assertTrue(sink.bytes().isEmpty())
    }

    @Test
    fun productionKdfIsExplicitlyDistinctFromTestOnlyKdf() {
        assertEquals(DsbkKdfParams(65536L, 3L, 4L), DsbkCrypto.PRODUCTION_KDF)
        assertNotEquals(TestOnlyKdf.PARAMS, DsbkCrypto.PRODUCTION_KDF)

        val crypto = DsbkCrypto.production()
        val plaintext = "production-kdf 中文".toByteArray()
        val v1 = crypto.encryptV1(plaintext, password())
        assertEquals(65536L, readUInt32LE(v1, 5))
        assertEquals(3L, readUInt32LE(v1, 9))
        assertEquals(4L, readUInt32LE(v1, 13))
        assertArrayEquals(plaintext, crypto.decryptToBytes(ByteArrayInputStream(v1), password(), 1024))

        val v2 = ByteArrayOutputStream()
        crypto.encryptV2(ByteArrayInputStream(plaintext), v2, password())
        val encrypted = v2.toByteArray()
        assertEquals(65536L, readUInt32LE(encrypted, 5))
        assertEquals(3L, readUInt32LE(encrypted, 9))
        assertEquals(4L, readUInt32LE(encrypted, 13))
        assertArrayEquals(plaintext, crypto.decryptToBytes(ByteArrayInputStream(encrypted), password(), 1024))
    }

    private fun assertDecrypts(
        engine: DsbkEngine,
        bytes: ByteArray,
        plaintext: ByteArray,
        max: Long,
    ) {
        val sink = CollectingSink(max)
        engine.decrypt(ByteArrayInputStream(bytes), password(), sink, max)
        assertTrue(sink.committed)
        assertFalse(sink.aborted)
        assertEquals(plaintext.size.toLong(), sink.stagedBytes)
        assertArrayEquals(plaintext, sink.bytes())
    }

    private fun assertProductionDecrypts(bytes: ByteArray, plaintext: ByteArray) {
        val actual = DsbkCrypto.production().decryptToBytes(
            ByteArrayInputStream(bytes),
            password(),
            plaintext.size.toLong(),
        )
        assertArrayEquals(plaintext, actual)
    }

    private fun assertRejected(
        engine: DsbkEngine,
        bytes: ByteArray,
        password: CharArray,
        max: Long,
        expected: DsbkError,
    ) {
        val sink = CollectingSink(max)
        val failure = expectDsbk { engine.decrypt(ByteArrayInputStream(bytes), password, sink, max) }
        assertEquals(expected, failure.error)
        assertTrue(sink.aborted)
        assertFalse(sink.committed)
        assertEquals(0, sink.stagedBytes)
        assertTrue(sink.bytes().isEmpty())
    }

    private fun encryptV2(engine: DsbkEngine, plaintext: ByteArray, salt: ByteArray, prefix: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        engine.encryptV2(
            ByteArrayInputStream(plaintext),
            output,
            password(),
            salt,
            TestOnlyKdf.PARAMS,
            prefix,
        )
        return output.toByteArray()
    }

    private fun multiChunkVector(engine: DsbkEngine): ByteArray {
        val plaintext = ByteArray(DsbkFormat.STREAM_CHUNK + 1) {
            ((it * 31 + 7) and 0xff).toByte()
        }
        val output = ByteArrayOutputStream()
        engine.encryptV2(
            ByteArrayInputStream(plaintext),
            output,
            password(),
            ByteArray(16) { 7 },
            TestOnlyKdf.PARAMS,
            ByteArray(7) { 0xaa.toByte() },
        )
        return output.toByteArray()
    }

    private fun threeChunkVector(engine: DsbkEngine): ByteArray {
        val plaintext = ByteArray(DsbkFormat.STREAM_CHUNK * 2 + 1) {
            ((it * 31 + 7) and 0xff).toByte()
        }
        val output = ByteArrayOutputStream()
        engine.encryptV2(
            ByteArrayInputStream(plaintext),
            output,
            password(),
            ByteArray(16) { 7 },
            TestOnlyKdf.PARAMS,
            ByteArray(7) { 0xaa.toByte() },
        )
        return output.toByteArray()
    }

    private fun swapFirstTwoChunks(encrypted: ByteArray): ByteArray {
        val cipherChunk = DsbkFormat.STREAM_CHUNK + DsbkFormat.GCM_TAG_LEN
        val first = DsbkFormat.V2_HEADER_LEN
        val second = first + cipherChunk
        val swapped = encrypted.copyOf()
        encrypted.copyOfRange(first, second).copyInto(swapped, second)
        encrypted.copyOfRange(second, second + cipherChunk).copyInto(swapped, first)
        return swapped
    }

    private fun assertRecord(expected: JsonObject, actual: ByteArray) {
        assertEquals(int(expected, "bytes"), actual.size)
        assertEquals(string(expected, "sha256"), sha256(actual))
        assertEquals(
            string(expected, "headerHex"),
            actual.copyOf(minOf(64, actual.size)).toHex(),
        )
        val fullHex = expected["fullHex"]?.jsonPrimitive?.contentOrNull
        if (fullHex != null) assertEquals(fullHex, actual.toHex())
    }

    private fun plaintextFor(record: JsonObject): ByteArray {
        val plain = record["plaintext"]!!.jsonObject
        val fullHex = plain["fullHex"]?.jsonPrimitive?.contentOrNull
        if (fullHex != null) return hex(fullHex)
        return ByteArray(int(plain, "bytes")) { ((it * 31 + 7) and 0xff).toByte() }
    }

    private fun case(id: String): JsonObject = artifact["cases"]!!.jsonArray
        .map { it.jsonObject }
        .first { string(it, "id") == id }

    private fun password(): CharArray = "pw-vector".toCharArray()

    private fun expectDsbk(block: () -> Unit): DsbkException {
        try {
            block()
            fail("expected DsbkException")
        } catch (error: DsbkException) {
            return error
        }
        error("unreachable")
    }

    private fun repositoryRoot(): File {
        var current = File(System.getProperty("user.dir")).absoluteFile
        while (true) {
            if (File(current, "config/baseline/crypto-golden.json").isFile) return current
            current = current.parentFile ?: error("repository root not found")
        }
    }

    private fun string(obj: JsonObject, key: String): String =
        obj[key]!!.jsonPrimitive.content

    private fun int(obj: JsonObject, key: String): Int =
        obj[key]!!.jsonPrimitive.int

    private fun hex(value: String): ByteArray {
        require(value.length % 2 == 0)
        return ByteArray(value.length / 2) { index ->
            value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    private fun flipLast(bytes: ByteArray): ByteArray =
        bytes.copyOf().also { it[it.lastIndex] = (it[it.lastIndex].toInt() xor 1).toByte() }

    private fun flipFirst(bytes: ByteArray): ByteArray =
        bytes.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }

    private fun withVersion(bytes: ByteArray, version: Int): ByteArray =
        bytes.copyOf().also { it[4] = version.toByte() }

    private fun withChunk(bytes: ByteArray, chunk: Int): ByteArray =
        bytes.copyOf().also { putUInt32LE(it, DsbkFormat.V2_CHUNK_OFFSET, chunk) }

    private fun putUInt32LE(bytes: ByteArray, offset: Int, value: Int) {
        ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(value)
    }

    private fun readUInt32LE(bytes: ByteArray, offset: Int): Long =
        ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
}

private class FixedRandomBytes(private val value: Int) : RandomBytesSource {
    override fun next(length: Int): ByteArray = ByteArray(length) { value.toByte() }
}

private class RecordingDeriver(
    private val delegate: DsbkKeyDeriver = Argon2idKeyDeriver(),
) : DsbkKeyDeriver {
    data class Call(
        val saltHex: String,
        val memoryKiB: Long,
        val iterations: Long,
        val parallelism: Long,
    )

    val calls = mutableListOf<Call>()

    override fun derive(password: CharArray, salt: ByteArray, params: DsbkKdfParams): ByteArray {
        calls += Call(
            salt.joinToString("") { "%02x".format(it.toInt() and 0xff) },
            params.memoryKiB,
            params.iterations,
            params.parallelism,
        )
        return delegate.derive(password, salt, params)
    }
}

private class CollectingSink(val limit: Long) : DsbkSink {
    private val output = ByteArrayOutputStream()
    var committed = false
        private set
    var aborted = false
        private set

    override var stagedBytes: Long = 0
        private set

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        if (committed || aborted) throw DsbkException(DsbkError.STAGING_FAILED)
        if (length.toLong() > limit - stagedBytes) {
            throw DsbkException(DsbkError.OUTPUT_LIMIT_EXCEEDED)
        }
        output.write(bytes, offset, length)
        stagedBytes += length
    }

    override fun commit() {
        committed = true
    }

    override fun abort() {
        aborted = true
        output.toByteArray().wipe()
        output.reset()
        stagedBytes = 0
    }

    fun bytes(): ByteArray = output.toByteArray()
}

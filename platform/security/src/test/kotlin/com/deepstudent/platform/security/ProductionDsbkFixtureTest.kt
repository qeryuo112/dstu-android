package com.deepstudent.platform.security

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Production-KDF evidence generated from the frozen desktop sources by
 * tools/s1/security-crypto-fixture.mjs. The oracle crypto-golden.json is not
 * modified; this fixture only adds non-ASCII password and production-cost
 * coverage that the cheap test-KDF oracle cannot express.
 */
class ProductionDsbkFixtureTest {
    private val fixture: JsonObject = Json.parseToJsonElement(resourceText()).jsonObject

    @Test
    fun productionV2ContainerMatchesDesktopBytesBothWays() {
        val engine = DsbkEngine(
            Argon2idKeyDeriver(),
            maxMemoryKiB = DsbkCrypto.PRODUCTION_MAX_MEMORY_KIB,
            maxChunkBytes = DsbkCrypto.PRODUCTION_MAX_CHUNK_BYTES,
        )
        val params = DsbkKdfParams(65536L, 3L, 4L)
        val password = string("password").toCharArray()
        val salt = hex(string("saltHex"))
        val prefix = hex(string("v2PrefixHex"))
        val plaintext = hex(fixture["plaintext"]!!.jsonObject["fullHex"]!!.jsonPrimitive.content)

        val output = ByteArrayOutputStream()
        engine.encryptV2(ByteArrayInputStream(plaintext), output, password, salt, params, prefix)
        val actual = output.toByteArray()
        assertArrayEquals(hex(fixture["v2"]!!.jsonObject["fullHex"]!!.jsonPrimitive.content), actual)

        val sink = ByteArrayDsbkSink(plaintext.size.toLong())
        engine.decrypt(ByteArrayInputStream(actual), password, sink, plaintext.size.toLong())
        assertArrayEquals(plaintext, sink.bytes())
    }

    @Test
    fun productionV1ContainerMatchesDesktopBytesBothWays() {
        val engine = productionEngine()
        val params = DsbkKdfParams(65536L, 3L, 4L)
        val password = string("password").toCharArray()
        val salt = hex(string("saltHex"))
        val nonce = hex(string("v1NonceHex"))
        val plaintext = hex(fixture["plaintext"]!!.jsonObject["fullHex"]!!.jsonPrimitive.content)

        val actual = engine.encryptV1(plaintext, password, salt, params, nonce)
        assertArrayEquals(hex(fixture["v1"]!!.jsonObject["fullHex"]!!.jsonPrimitive.content), actual)

        val sink = ByteArrayDsbkSink(plaintext.size.toLong())
        engine.decrypt(ByteArrayInputStream(actual), password, sink, plaintext.size.toLong())
        assertArrayEquals(plaintext, sink.bytes())
    }

    /**
     * Writes random-salt production containers consumed by
     * `security-crypto-fixture.mjs --verify-android`. The desktop decrypt is
     * the opposite direction of the checked-in desktop fixtures above.
     */
    @Test
    fun androidProductionContainersExportForDesktopDecrypt() {
        val crypto = DsbkCrypto.production()
        val password = string("password").toCharArray()
        val plaintext = ByteArray(DsbkFormat.STREAM_CHUNK + 1) {
            ((it * 31 + 7) and 0xff).toByte()
        }
        val directory = exportDirectory()
        check(directory.isDirectory || directory.mkdirs()) { "export directory is unavailable" }
        val entries = listOf(1, 2).map { version ->
            val bytes = if (version == 1) {
                crypto.encryptV1(plaintext, password)
            } else {
                ByteArrayOutputStream().also { output ->
                    crypto.encryptV2(ByteArrayInputStream(plaintext), output, password)
                }.toByteArray()
            }
            val sink = ByteArrayDsbkSink(plaintext.size.toLong())
            crypto.decrypt(ByteArrayInputStream(bytes), password, sink, plaintext.size.toLong())
            assertArrayEquals(plaintext, sink.bytes())
            ExportEntry("android-v$version.dsbk", version, bytes, plaintext)
        }
        for (entry in entries) {
            File(directory, entry.file).writeBytes(entry.bytes)
        }
        File(directory, "manifest.json").writeText(exportManifest(entries))
    }

    @Test
    fun productionBudgetRejectsOversizedHeaderChunkBeforeKdf() {
        val deriver = CountingDeriver()
        val engine = DsbkEngine(
            deriver,
            maxMemoryKiB = DsbkCrypto.PRODUCTION_MAX_MEMORY_KIB,
            maxChunkBytes = DsbkCrypto.PRODUCTION_MAX_CHUNK_BYTES,
        )
        val header = DsbkHeaderCodec.writeV2(
            DsbkKdfParams(8L, 1L, 1L),
            ByteArray(16) { 7 },
            ByteArray(7) { 0xaa.toByte() },
        )
        putUInt32LE(header, DsbkFormat.V2_CHUNK_OFFSET, DsbkCrypto.PRODUCTION_MAX_CHUNK_BYTES + 1)
        val sink = ByteArrayDsbkSink(1024)
        val failure = expectDsbk { engine.decrypt(ByteArrayInputStream(header), password().toCharArray(), sink, 1024) }
        assertEquals(DsbkError.CHUNK_INVALID, failure.error)
        assertEquals(0, deriver.calls)
        assertFalse(sink.stagedBytes > 0)
        assertTrue(runCatching { sink.bytes() }.isFailure)
    }

    @Test
    fun fixtureRecordsFrozenDesktopProvenance() {
        val source = fixture["source"]!!.jsonObject
        assertEquals("e6b2657fd59014a73f5bbb1d14c2db732cf23e87", source["commit"]!!.jsonPrimitive.content)
        val files = source["files"]!!.jsonArray.map { it.jsonObject }
        assertEquals(2, files.size)
        assertTrue(files.any { it["file"]!!.jsonPrimitive.content.endsWith("backup-crypto.ts") })
        assertTrue(files.any { it["file"]!!.jsonPrimitive.content.endsWith("dsbk-stream.ts") })
        assertEquals(65536L, fixture["kdf"]!!.jsonObject["memoryKiB"]!!.jsonPrimitive.content.toLong())
        assertEquals(3L, fixture["kdf"]!!.jsonObject["iterations"]!!.jsonPrimitive.content.toLong())
        assertEquals(4L, fixture["kdf"]!!.jsonObject["parallelism"]!!.jsonPrimitive.content.toLong())
    }

    @Test
    fun productionBoundaryContainersAndFinalTagsMatchFrozenDesktop() {
        val engine = productionEngine()
        val password = password().toCharArray()
        try {
            for (record in fixture["cases"]!!.jsonArray.map { it.jsonObject }) {
                val plainRecord = record["plaintext"]!!.jsonObject
                val size = plainRecord["bytes"]!!.jsonPrimitive.content.toInt()
                val plain = ByteArray(size) { ((it * 31 + 7) and 0xff).toByte() }
                val expected = record["v2"]!!.jsonObject
                val output = ByteArrayOutputStream()
                engine.encryptV2(ByteArrayInputStream(plain), output, password,
                    hex(string("saltHex")), DsbkCrypto.PRODUCTION_KDF, hex(string("v2PrefixHex")))
                val encrypted = output.toByteArray()
                assertEquals(expected["bytes"]!!.jsonPrimitive.content.toInt(), encrypted.size)
                assertEquals(expected["sha256"]!!.jsonPrimitive.content, encrypted.sha256Hex())
                assertArrayEquals(hex(expected["tailHex"]!!.jsonPrimitive.content), encrypted.takeLast(32).toByteArray())
                val actual = DsbkCrypto.production().decryptToBytes(ByteArrayInputStream(encrypted), password, size.toLong())
                assertArrayEquals(plain, actual)
                actual.wipe()
            }
        } finally { password.fill('\u0000') }
    }

    @Test
    fun productionKdfMatchesUtf16ReplacementNulAndNonNormalizedDesktopPasswords() {
        val salt = hex(string("saltHex"))
        val deriver = Argon2idKeyDeriver()
        for (record in fixture["kdfVectors"]!!.jsonArray.map { it.jsonObject }) {
            val password = record["passwordUtf16"]!!.jsonArray.map { it.jsonPrimitive.content.toInt().toChar() }.toCharArray()
            val encoded = Utf8Bytes.encode(password)
            val key = deriver.derive(password, salt, DsbkCrypto.PRODUCTION_KDF)
            try {
                assertArrayEquals(hex(record["passwordUtf8Hex"]!!.jsonPrimitive.content), encoded)
                assertEquals(record["derivedKeySha256"]!!.jsonPrimitive.content, key.sha256Hex())
            } finally {
                password.fill('\u0000')
                encoded.wipe()
                key.wipe()
            }
        }
    }

    private fun productionEngine(): DsbkEngine = DsbkEngine(
        Argon2idKeyDeriver(),
        maxMemoryKiB = DsbkCrypto.PRODUCTION_MAX_MEMORY_KIB,
        maxChunkBytes = DsbkCrypto.PRODUCTION_MAX_CHUNK_BYTES,
    )

    private fun exportDirectory(): File {
        val cwd = File(System.getProperty("user.dir")).absoluteFile
        val module = if (File(cwd, "src/test/resources/security").isDirectory) {
            cwd
        } else {
            File(repositoryRoot(), "platform/security")
        }
        return File(module, "build/security-export")
    }

    private fun repositoryRoot(): File {
        var current = File(System.getProperty("user.dir")).absoluteFile
        while (true) {
            if (File(current, "config/baseline/crypto-golden.json").isFile) return current
            current = current.parentFile ?: error("repository root not found")
        }
    }

    private fun exportManifest(entries: List<ExportEntry>): String = buildString {
        append("{\n")
        append("  \"format\": \"dstu.security.android-export.v1\",\n")
        append("  \"fixture\": \"dsbk-production-golden.json\",\n")
        append("  \"testRevision\": \"s1.23-security-hardening-v2\",\n")
        append("  \"containers\": [\n")
        entries.forEachIndexed { index, entry ->
            append("    {\"file\": \"").append(entry.file).append("\", ")
            append("\"version\": ").append(entry.version).append(", ")
            append("\"bytes\": ").append(entry.bytes.size).append(", ")
            append("\"sha256\": \"").append(entry.bytes.sha256Hex()).append("\", ")
            append("\"plaintextBytes\": ").append(entry.plaintext.size).append(", ")
            append("\"plaintextSha256\": \"").append(entry.plaintext.sha256Hex()).append("\"}")
            if (index != entries.lastIndex) append(',')
            append('\n')
        }
        append("  ]\n}\n")
    }

    private fun ByteArray.sha256Hex(): String =
        MessageDigest.getInstance("SHA-256").digest(this)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun resourceText(): String =
        checkNotNull(javaClass.getResourceAsStream("/security/dsbk-production-golden.json")) {
            "production DSBK fixture is missing"
        }.bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun password(): String = string("password")

    private fun string(key: String): String = fixture[key]!!.jsonPrimitive.content

    private fun hex(value: String): ByteArray {
        require(value.length % 2 == 0)
        return ByteArray(value.length / 2) { index ->
            value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    private fun putUInt32LE(bytes: ByteArray, offset: Int, value: Int) {
        java.nio.ByteBuffer.wrap(bytes, offset, 4)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .putInt(value)
    }

    private fun expectDsbk(block: () -> Unit): DsbkException {
        try {
            block()
            fail("expected DsbkException")
        } catch (error: DsbkException) {
            return error
        }
        error("unreachable")
    }
}

private data class ExportEntry(
    val file: String,
    val version: Int,
    val bytes: ByteArray,
    val plaintext: ByteArray,
)

private class CountingDeriver : DsbkKeyDeriver {
    var calls = 0
        private set

    override fun derive(password: CharArray, salt: ByteArray, params: DsbkKdfParams): ByteArray {
        calls++
        return ByteArray(DsbkFormat.KEY_LEN)
    }
}

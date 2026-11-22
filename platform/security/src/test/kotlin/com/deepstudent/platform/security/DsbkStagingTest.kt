package com.deepstudent.platform.security

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DsbkStagingTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private val engine = DsbkEngine(
        Argon2idKeyDeriver(),
        ByteRandomSource(0xaa),
    )

    @Test
    fun tamperedAndTruncatedStreamsLeaveNoActiveFileOrStagingPart() {
        val bytes = goldenPlusOne()
        val target = File(temporary.root, "active.bin")
        val area = DsbkStagingArea(temporary.root, 2L * 1024 * 1024)
        try {
            val tampered = bytes.copyOf().also {
                it[it.lastIndex] = (it[it.lastIndex].toInt() xor 1).toByte()
            }
            val sink = area.createSink(target)
            val failure = runCatching {
                engine.decrypt(ByteArrayInputStream(tampered), password(), sink, 2L * 1024 * 1024)
            }.exceptionOrNull()
            assertTrue(failure is DsbkException)
            assertEquals(DsbkError.AUTHENTICATION_FAILED, (failure as DsbkException).error)
            assertEquals(0, sink.stagedBytes)
            assertFalse(target.exists())
            assertEquals(0, area.cleanupOrphans())
            assertTrue(temporary.root.listFiles()!!.none { it.name.endsWith(".part") })
        } finally {
            area.close()
        }
    }

    @Test
    fun stagingCapacityIsBoundedAndFailureCleansTemporaryFile() {
        val target = File(temporary.root, "too-large.bin")
        val area = DsbkStagingArea(temporary.root, 1024L * 1024L)
        try {
            val sink = area.createSink(target)
            val failure = runCatching {
                engine.decrypt(
                    ByteArrayInputStream(goldenPlusOne()),
                    password(),
                    sink,
                    1024L * 1024L,
                )
            }.exceptionOrNull()
            assertTrue(failure is DsbkException)
            assertEquals(DsbkError.OUTPUT_LIMIT_EXCEEDED, (failure as DsbkException).error)
            assertEquals(0, sink.stagedBytes)
            assertFalse(target.exists())
            assertTrue(temporary.root.listFiles()!!.none { it.name.endsWith(".part") })
        } finally {
            area.close()
        }
    }

    @Test
    fun successfulStreamActivatesOnlyAfterFullAuthentication() {
        val target = File(temporary.root, "active.bin")
        val area = DsbkStagingArea(temporary.root, 1024L * 1024L + 1L)
        try {
            val sink = area.createSink(target)
            val encrypted = goldenPlusOne()
            val expected = ByteArray(1024 * 1024 + 1) { ((it * 31 + 7) and 0xff).toByte() }
            engine.decrypt(ByteArrayInputStream(encrypted), password(), sink, expected.size.toLong())
            assertTrue(target.isFile)
            assertEquals(1024L * 1024L + 1L, target.length())
            assertEquals(sha256(expected), sha256(target.readBytes()))
            assertTrue(temporary.root.listFiles()!!.none { it.name.endsWith(".part") })
        } finally {
            area.close()
        }
    }

    @Test
    fun swappedChunksAreRejectedAndExistingTargetSurvives() {
        val encrypted = multiChunk()
        val swapped = swapFirstTwoChunks(encrypted)
        val target = File(temporary.root, "active.bin").apply { writeText("existing-active") }
        val area = DsbkStagingArea(temporary.root, 3L * 1024 * 1024)
        try {
            val sink = area.createSink(target)
            val failure = runCatching {
                engine.decrypt(ByteArrayInputStream(swapped), password(), sink, 3L * 1024 * 1024)
            }.exceptionOrNull()
            assertEquals(DsbkError.AUTHENTICATION_FAILED, (failure as DsbkException).error)
            assertEquals(0, sink.stagedBytes)
            assertEquals("existing-active", target.readText())
            assertTrue(temporary.root.listFiles()!!.none { it.name.endsWith(".part") })
        } finally {
            area.close()
        }
    }

    @Test
    fun closeAbortsActiveSinkWithoutPublishingPartialData() {
        val target = File(temporary.root, "active.bin")
        val area = DsbkStagingArea(temporary.root, 1024L)
        val sink = area.createSink(target)
        sink.write(byteArrayOf(1, 2, 3, 4))
        area.close()
        assertEquals(0, sink.stagedBytes)
        assertThrows(DsbkException::class.java) { sink.write(byteArrayOf(5)) }
        assertFalse(target.exists())
        assertTrue(temporary.root.listFiles()!!.none { it.name.endsWith(".part") })
    }

    @Test
    fun orphanCleanupDoesNotDeleteAnActiveSink() {
        val area = DsbkStagingArea(temporary.root, 1024L)
        try {
            val sink = area.createSink(File(temporary.root, "active.bin"))
            sink.write(byteArrayOf(9))
            assertEquals(0, area.cleanupOrphans())
            sink.write(byteArrayOf(8))
            sink.abort()
            assertEquals(0, area.cleanupOrphans())
        } finally {
            area.close()
        }
    }

    @Test
    fun stagingTargetSymlinkIsRejectedBeforeAnyDecrypt() {
        val victim = File(temporary.root, "victim.bin").apply { writeText("victim-content") }
        val target = File(temporary.root, "active.bin")
        Files.createSymbolicLink(target.toPath(), victim.toPath())
        val area = DsbkStagingArea(File(temporary.root, "staging"), 1024L)
        try {
            val failure = runCatching { area.createSink(target) }.exceptionOrNull()
            assertTrue(failure is DsbkException)
            assertEquals(DsbkError.STAGING_FAILED, (failure as DsbkException).error)
            assertEquals("victim-content", victim.readText())
            assertEquals(0, area.cleanupOrphans())
        } finally {
            area.close()
        }
    }

    @Test
    fun stagingDirectorySymlinkIsRejected() {
        val real = File(temporary.root, "real-staging").apply { check(mkdirs()) }
        val link = File(temporary.root, "staging-link")
        Files.createSymbolicLink(link.toPath(), real.toPath())
        val failure = runCatching { DsbkStagingArea(link, 1024L) }.exceptionOrNull()
        assertTrue(failure is DsbkException)
        assertEquals(DsbkError.STAGING_FAILED, (failure as DsbkException).error)
    }

    @Test
    fun v2DecryptWritesBoundedChunksInsteadOfWholeOutput() {
        val plaintext = ByteArray(DsbkFormat.STREAM_CHUNK * 3 + 17) {
            ((it * 31 + 7) and 0xff).toByte()
        }
        val output = ByteArrayOutputStream()
        engine.encryptV2(
            ByteArrayInputStream(plaintext),
            output,
            password(),
            ByteArray(16) { 7 },
            DsbkKdfParams(8L, 1L, 1L),
            ByteArray(7) { 0xaa.toByte() },
        )
        val sink = DigestSink(plaintext.size.toLong())
        engine.decrypt(
            ByteArrayInputStream(output.toByteArray()),
            password(),
            sink,
            plaintext.size.toLong(),
        )
        assertTrue(sink.committed)
        assertEquals(DsbkFormat.STREAM_CHUNK, sink.maxWrite)
        assertEquals(4, sink.writeCount)
        assertEquals(plaintext.size.toLong(), sink.stagedBytes)
        assertEquals(sha256(plaintext), sink.hexDigest())
    }

    private fun multiChunk(): ByteArray {
        val plaintext = ByteArray(2 * 1024 * 1024 + 1) { ((it * 31 + 7) and 0xff).toByte() }
        val output = java.io.ByteArrayOutputStream()
        engine.encryptV2(
            ByteArrayInputStream(plaintext),
            output,
            password(),
            ByteArray(16) { 7 },
            DsbkKdfParams(8L, 1L, 1L),
            ByteArray(7) { 0xaa.toByte() },
        )
        return output.toByteArray()
    }

    private fun swapFirstTwoChunks(encrypted: ByteArray): ByteArray {
        val cipherChunk = DsbkFormat.STREAM_CHUNK + DsbkFormat.GCM_TAG_LEN
        val firstStart = DsbkFormat.V2_HEADER_LEN
        val secondStart = firstStart + cipherChunk
        val swapped = encrypted.copyOf()
        encrypted.copyOfRange(firstStart, secondStart).copyInto(swapped, secondStart)
        encrypted.copyOfRange(secondStart, secondStart + cipherChunk).copyInto(swapped, firstStart)
        return swapped
    }

    private fun goldenPlusOne(): ByteArray {
        val plaintext = ByteArray(1024 * 1024 + 1) { ((it * 31 + 7) and 0xff).toByte() }
        val output = java.io.ByteArrayOutputStream()
        engine.encryptV2(
            ByteArrayInputStream(plaintext),
            output,
            password(),
            ByteArray(16) { 7 },
            DsbkKdfParams(8L, 1L, 1L),
            ByteArray(7) { 0xaa.toByte() },
        )
        return output.toByteArray()
    }

    private fun password(): CharArray = "pw-vector".toCharArray()

    private fun hex(value: String): ByteArray = ByteArray(value.length / 2) {
        value.substring(it * 2, it * 2 + 2).toInt(16).toByte()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

private class ByteRandomSource(private val value: Int) : RandomBytesSource {
    override fun next(length: Int): ByteArray = ByteArray(length) { value.toByte() }
}

private class DigestSink(private val limit: Long) : DsbkSink {
    private val digest = MessageDigest.getInstance("SHA-256")
    var committed = false
        private set
    var maxWrite = 0
        private set
    var writeCount = 0
        private set

    override var stagedBytes: Long = 0
        private set

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        if (committed) throw DsbkException(DsbkError.STAGING_FAILED)
        if (offset < 0 || length < 0 || offset > bytes.size - length) {
            throw DsbkException(DsbkError.STAGING_FAILED)
        }
        if (length.toLong() > limit - stagedBytes) {
            throw DsbkException(DsbkError.OUTPUT_LIMIT_EXCEEDED)
        }
        maxWrite = maxOf(maxWrite, length)
        writeCount++
        digest.update(bytes, offset, length)
        stagedBytes += length
    }

    override fun commit() {
        committed = true
    }

    override fun abort() {
        stagedBytes = 0
    }

    fun hexDigest(): String {
        check(committed) { "digest is available only after commit" }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}

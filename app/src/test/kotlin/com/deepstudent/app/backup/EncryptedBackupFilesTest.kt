package com.deepstudent.app.backup

import com.deepstudent.platform.security.DsbkCrypto
import com.deepstudent.platform.security.DsbkStagingArea
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.io.RandomAccessFile
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Pure JVM private-file tests. No real DataSpace, APK or device is exercised. */
class EncryptedBackupFilesTest {
    private lateinit var root: File
    private lateinit var stage: DsbkStagingArea
    private lateinit var files: EncryptedBackupFiles
    private val crypto = DsbkCrypto.production()
    private val password = "fixture-password".toCharArray()
    private val limit = 8L * 1024 * 1024

    @Before fun setup() {
        root = Files.createTempDirectory("s214-file-test-").toFile()
        stage = DsbkStagingArea(File(root, "staging"), limit)
        files = EncryptedBackupFiles(root, stage, crypto, limit - 1024, limit)
    }
    @After fun teardown() {
        stage.close()
        password.fill('\u0000')
        root.deleteRecursively()
    }

    @Test fun v1AndV2AuthenticateAndOnlyThenPublishValidatedZip() {
        val source = createValidZip()
        val original = source.readBytes()
        val proof = files.validateZip(source)
        val v2 = File(root, "backup-v2.dsbk")
        files.encryptZip(proof, v2, password)
        assertEquals(2, files.preflight(v2).version)
        val restored = File(root, "restored.zip").apply { writeText("old") }
        files.decryptZip(v2, restored, password)
        assertArrayEquals(original, restored.readBytes())
        val v1 = File(root, "backup-v1.dsbk").apply { writeBytes(crypto.encryptV1(original, password)) }
        assertEquals(1, files.preflight(v1).version)
        files.decryptZip(v1, restored, password)
        assertArrayEquals(original, restored.readBytes())
    }

    @Test fun wrongPasswordAndTamperingPreserveExistingFileAndCleanParts() {
        val source = createValidZip()
        val encrypted = File(root, "input.dsbk")
        files.encryptZip(files.validateZip(source), encrypted, password)
        val target = File(root, "target.zip").apply { writeText("unchanged") }
        val error = expect(EncryptedBackupFileError.AUTHENTICATION_FAILED) {
            files.decryptZip(encrypted, target, "not-the-password".toCharArray())
        }
        assertFalse(error.toString().contains("not-the-password"))
        assertEquals("unchanged", target.readText())
        val corrupt = encrypted.readBytes().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        encrypted.writeBytes(corrupt)
        expect(EncryptedBackupFileError.AUTHENTICATION_FAILED) { files.decryptZip(encrypted, target, password) }
        assertEquals("unchanged", target.readText())
        assertNoTemporaryPlaintext()
    }

    @Test fun invalidZipAndChangedValidatedSourceNeverPublish() {
        val notZip = File(root, "not.zip").apply { writeText("not-a-zip") }
        expect(EncryptedBackupFileError.ZIP_INVALID) { files.validateZip(notZip) }
        val encrypted = File(root, "invalid.dsbk")
        notZip.inputStream().use { input -> encrypted.outputStream().use { crypto.encryptV2(input, it, password) } }
        val target = File(root, "target.zip").apply { writeText("unchanged") }
        expect(EncryptedBackupFileError.ZIP_INVALID) { files.decryptZip(encrypted, target, password) }
        assertEquals("unchanged", target.readText())
        val valid = createValidZip()
        val proof = files.validateZip(valid)
        valid.appendText("changed")
        val output = File(root, "output.dsbk").apply { writeText("old encrypted") }
        expect(EncryptedBackupFileError.ZIP_CHANGED) { files.encryptZip(proof, output, password) }
        assertEquals("old encrypted", output.readText())
        assertNoTemporaryPlaintext()
    }

    @Test fun cancellationAndBudgetsDoNotPublishOrLeakParts() {
        val source = createValidZip()
        val proof = files.validateZip(source)
        val target = File(root, "target.dsbk").apply { writeText("old") }
        var checks = 0
        expect(EncryptedBackupFileError.CANCELLED) {
            files.encryptZip(proof, target, password) { ++checks > 2 }
        }
        assertEquals("old", target.readText())
        val encrypted = File(root, "input.dsbk")
        files.encryptZip(proof, encrypted, password)
        val recovered = File(root, "output.zip").apply { writeText("old recovered") }
        checks = 0
        expect(EncryptedBackupFileError.CANCELLED) {
            files.decryptZip(encrypted, recovered, password) { ++checks > 3 }
        }
        assertEquals("old recovered", recovered.readText())
        val tiny = EncryptedBackupFiles(root, stage, crypto, 8, limit)
        expect(EncryptedBackupFileError.ZIP_INVALID) { tiny.validateZip(source) }
        assertNoTemporaryPlaintext()
    }

    @Test fun cancellationAfterPartialChunkPreservesOldOutputsAndRemovesStages() {
        val source = createValidZip(paddingDirectories = 40)
        assertTrue(source.length() > 1024 * 1024)
        val proof = files.validateZip(source)
        val encrypted = File(root, "large.dsbk")
        files.encryptZip(proof, encrypted, password)
        val target = File(root, "target.zip").apply { writeText("old data") }
        var observedPlaintext = false
        expect(EncryptedBackupFileError.CANCELLED) {
            files.decryptZip(encrypted, target, password) {
                val written = File(root, "staging").listFiles()!!.any { it.name.endsWith(".part") && it.length() > 0 }
                observedPlaintext = observedPlaintext || written
                written
            }
        }
        assertTrue("cancellation must occur after plaintext staging begins", observedPlaintext)
        assertEquals("old data", target.readText())
        assertNoTemporaryPlaintext()
        val oldCipher = File(root, "old.dsbk").apply { writeText("old ciphertext") }
        var observedCiphertext = false
        expect(EncryptedBackupFileError.CANCELLED) {
            files.encryptZip(proof, oldCipher, password) {
                val written = File(root, "staging").listFiles()!!.any { it.name.endsWith(".part") && it.length() > 44 }
                observedCiphertext = observedCiphertext || written
                written
            }
        }
        assertTrue("cancellation must occur after ciphertext staging begins", observedCiphertext)
        assertEquals("old ciphertext", oldCipher.readText())
        assertNoTemporaryPlaintext()
    }

    @Test fun largeV1IsRejectedForExplicitDesktopConversionBeforeKdf() {
        val encrypted = File(root, "large-v1.dsbk")
        encrypted.writeBytes(crypto.encryptV1(byteArrayOf(1), password))
        RandomAccessFile(encrypted, "rw").use { it.setLength(DsbkCrypto.MAX_IN_MEMORY_BYTES + 62) }
        DsbkStagingArea(File(root, "large-staging"), 128L * 1024 * 1024).use { largeStage ->
            val reader = EncryptedBackupFiles(root, largeStage, crypto, 128L * 1024 * 1024, 129L * 1024 * 1024)
            expect(EncryptedBackupFileError.V1_CONVERSION_REQUIRED) { reader.preflight(encrypted) }
            val target = File(root, "output.zip").apply { writeText("old") }
            expect(EncryptedBackupFileError.V1_CONVERSION_REQUIRED) { reader.decryptZip(encrypted, target, password) }
            assertEquals("old", target.readText())
        }
        assertNoTemporaryPlaintext()
    }

    @Test fun fileScopeAndProofOwnershipAreEnforced() {
        val source = createValidZip()
        val proof = files.validateZip(source)
        val other = EncryptedBackupFiles(root, stage, crypto, limit - 1024, limit)
        expect(EncryptedBackupFileError.ZIP_CHANGED) { other.encryptZip(proof, File(root, "x"), password) }
        expect(EncryptedBackupFileError.FILE_INVALID) { files.encryptZip(proof, source, password) }
        expect(EncryptedBackupFileError.FILE_INVALID) { files.validateZip(File(root.parentFile, "outside.zip")) }
        val link = File(root, "link.zip")
        Files.createSymbolicLink(link.toPath(), source.toPath())
        expect(EncryptedBackupFileError.FILE_INVALID) { files.validateZip(link) }
    }

    private fun expect(code: EncryptedBackupFileError, block: () -> Unit): EncryptedBackupFileException {
        val failure = try { block(); fail("expected $code"); null } catch (error: EncryptedBackupFileException) { error }
        assertEquals(code, failure!!.error)
        assertEquals(code.fixedMessage, failure.message)
        return failure
    }

    private fun assertNoTemporaryPlaintext() {
        assertTrue(root.listFiles()!!.none { it.name.startsWith(".authenticated-") })
        assertTrue(File(root, "staging").listFiles()!!.none { it.name.endsWith(".part") })
    }

    private fun createValidZip(paddingDirectories: Int = 0): File {
        val fixture = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .map { File(it, "services/backup/backend/src/test/resources/desktop-v3-full.json") }
            .first { it.isFile }
        val archive = File(root, "source.zip")
        ZipOutputStream(archive.outputStream()).use { zip ->
            fun entry(name: String, bytes: ByteArray) { zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
            repeat(paddingDirectories) { number ->
                val directory = ZipEntry("padding$number/").apply { comment = "x".repeat(60_000) }
                zip.putNextEntry(directory); zip.closeEntry()
            }
            entry("manifest.json", fixture.readBytes())
            listOf("vfs", "llm_usage", "chat_v2", "mistakes").forEach { entry("$it.db", "sqlite:$it".toByteArray()) }
        }
        return archive
    }
}

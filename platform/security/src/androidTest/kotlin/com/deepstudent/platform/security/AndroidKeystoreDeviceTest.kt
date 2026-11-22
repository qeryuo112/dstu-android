package com.deepstudent.platform.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidKeystoreDeviceTest {
    private lateinit var context: Context
    private lateinit var root: File
    private lateinit var host: SecurityForegroundHost

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        root = File(context.cacheDir.canonicalFile, "security-device-${UUID.randomUUID()}").apply { check(mkdirs()) }
        host = SecurityForegroundHost().also { it.start() }
    }

    @After
    fun tearDown() {
        runCatching { AndroidKeystoreKeyWrapper(alias()).deleteKey() }
        root.deleteRecursively()
        host.close()
    }

    @Test
    fun androidKeystoreCredentialsSurviveReopenAndKeyLossFailsClosed() {
        val events = mutableListOf<SecurityAuditEvent>()
        val audit = SecurityAudit { events += it }
        val masterFile = File(root, "master.key")
        val key = CredentialKey.of("device.credential")
        val secret = "Android-凭据-🔐-${UUID.randomUUID()}"
        val store = EncryptedCredentialStore(
            File(root, "credentials"),
            WrappedMasterKeyStore(masterFile, AndroidKeystoreKeyWrapper(alias()), audit = audit),
            audit,
        )
        val value = SecretBytes.fromUtf8(secret.toCharArray())
        store.put(key, value)
        value.close()
        store.close()

        val reopened = EncryptedCredentialStore(
            File(root, "credentials"),
            WrappedMasterKeyStore(masterFile, AndroidKeystoreKeyWrapper(alias()), audit = audit),
            audit,
        )
        reopened.get(key).use { assertEquals(secret, String(it, Charsets.UTF_8)) }
        reopened.close()

        val secretBytes = secret.toByteArray(Charsets.UTF_8)
        for (file in root.walkTopDown().filter { it.isFile }) {
            assertFalse(file.name.contains(secret))
            assertFalse(contains(file.readBytes(), secretBytes))
        }
        assertTrue(events.none { it.toString().contains(secret) })

        val record = root.walkTopDown().first { it.isFile && it.name.endsWith(".dsc") }
        flipLast(record)
        val tampered = EncryptedCredentialStore(
            File(root, "credentials"),
            WrappedMasterKeyStore(masterFile, AndroidKeystoreKeyWrapper(alias()), audit = audit),
            audit,
        )
        val integrity = expectCredential { tampered.get(key) }
        assertEquals(CredentialStoreError.INTEGRITY_FAILURE, integrity.error)
        tampered.close()

        val wrappedBefore = masterFile.readBytes()
        AndroidKeystoreKeyWrapper(alias()).deleteKey()
        val lost = expectCredential {
            WrappedMasterKeyStore(masterFile, AndroidKeystoreKeyWrapper(alias())).loadOrCreate()
        }
        assertEquals(CredentialStoreError.MASTER_KEY_UNAVAILABLE, lost.error)
        assertArrayEquals(wrappedBefore, masterFile.readBytes())
    }

    @Test
    fun productionDsbkV2RejectsWrongPasswordAndTamperWithoutPublishing() {
        val events = mutableListOf<SecurityAuditEvent>()
        val crypto = DsbkCrypto.production(SecurityAudit { events += it })
        val password = "设备密码-🔐".toCharArray()
        val marker = "DSBK-PLAINTEXT-MARKER-${UUID.randomUUID()}".toByteArray()
        val plaintext = ByteArray(1024 * 1024 + 1) { ((it * 31 + 7) and 0xff).toByte() }
        marker.copyInto(plaintext, 0)
        val encryptedFile = File(root, "backup.dsbk")
        FileOutputStream(encryptedFile).use { output ->
            crypto.encryptV2(java.io.ByteArrayInputStream(plaintext), output, password)
        }
        assertFalse(contains(encryptedFile.readBytes(), marker))

        val area = DsbkStagingArea(File(root, "staging"), 2L * 1024 * 1024)
        try {
            val target = File(root, "restored.bin")
            val sink = area.createSink(target)
            FileInputStream(encryptedFile).use { input ->
                crypto.decrypt(input, password, sink, plaintext.size.toLong())
            }
            assertArrayEquals(plaintext, target.readBytes())

            val keep = File(root, "keep.bin").apply { writeText("existing-active") }
            val wrongSink = area.createSink(keep)
            val wrong = expectDsbk {
                FileInputStream(encryptedFile).use { input ->
                    crypto.decrypt(input, "wrong-password".toCharArray(), wrongSink, plaintext.size.toLong())
                }
            }
            assertEquals(DsbkError.AUTHENTICATION_FAILED, wrong.error)
            assertEquals("existing-active", keep.readText())

            val tampered = File(root, "tampered.dsbk").apply {
                writeBytes(encryptedFile.readBytes().also {
                    it[it.lastIndex] = (it[it.lastIndex].toInt() xor 1).toByte()
                })
            }
            val tamperSink = area.createSink(keep)
            val tamper = expectDsbk {
                FileInputStream(tampered).use { input ->
                    crypto.decrypt(input, password, tamperSink, plaintext.size.toLong())
                }
            }
            assertEquals(DsbkError.AUTHENTICATION_FAILED, tamper.error)
            assertEquals("existing-active", keep.readText())
            assertEquals(0, area.cleanupOrphans())
            assertTrue(events.none { it.toString().contains("wrong-password") })
        } finally {
            area.close()
        }
    }

    @Test
    fun productionDsbkV1RoundTripsOnDevice() {
        val crypto = DsbkCrypto.production()
        val password = "v1-设备-🔐".toCharArray()
        val plaintext = "v1-中文-🔐-payload".toByteArray(Charsets.UTF_8)
        val encrypted = crypto.encryptV1(plaintext, password)
        val decrypted = crypto.decryptToBytes(java.io.ByteArrayInputStream(encrypted), password, 1024)
        assertArrayEquals(plaintext, decrypted)
        assertEquals(65536L, readUInt32LE(encrypted, 5))
        assertEquals(3L, readUInt32LE(encrypted, 9))
        assertEquals(4L, readUInt32LE(encrypted, 13))
    }

    @Test
    fun stagingRestartCleanupAndCloseLifecycle() {
        val staging = File(root, "staging").apply { check(mkdirs()) }
        File(staging, ".dstu-stale.part").writeBytes(ByteArray(64) { 7 })
        val area = DsbkStagingArea(staging, 1024L)
        assertEquals(1, area.cleanupOrphans())
        val target = File(root, "active.bin")
        val sink = area.createSink(target)
        sink.write(byteArrayOf(1, 2, 3))
        area.close()
        assertEquals(0, sink.stagedBytes)
        assertFalse(target.exists())
        assertTrue(staging.listFiles()!!.none { it.name.endsWith(".part") })
    }

    private fun alias(): String = "${AndroidKeystoreKeyWrapper.DEFAULT_ALIAS}.device.${root.name}"

    private fun flipLast(file: File) {
        RandomAccessFile(file, "rw").use { access ->
            val offset = access.length() - 1
            access.seek(offset)
            val value = access.read()
            access.seek(offset)
            access.write(value xor 1)
        }
    }

    private fun contains(haystack: ByteArray, needle: ByteArray): Boolean {
        if (needle.isEmpty()) return true
        if (needle.size > haystack.size) return false
        for (start in 0..haystack.size - needle.size) {
            var match = true
            for (index in needle.indices) {
                if (haystack[start + index] != needle[index]) {
                    match = false
                    break
                }
            }
            if (match) return true
        }
        return false
    }

    private fun expectCredential(block: () -> Any?): CredentialStoreException {
        try {
            block()
            fail("expected CredentialStoreException")
        } catch (error: CredentialStoreException) {
            return error
        }
        error("unreachable")
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

    private fun readUInt32LE(bytes: ByteArray, offset: Int): Long =
        java.nio.ByteBuffer.wrap(bytes, offset, 4)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .int.toLong() and 0xffffffffL
}

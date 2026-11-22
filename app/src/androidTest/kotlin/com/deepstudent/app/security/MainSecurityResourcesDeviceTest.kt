package com.deepstudent.app.security

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.deepstudent.app.editor.EditorTestHost
import androidx.test.platform.app.InstrumentationRegistry
import com.deepstudent.platform.security.AndroidKeystoreKeyWrapper
import com.deepstudent.platform.security.CredentialKey
import com.deepstudent.platform.security.CredentialStoreError
import com.deepstudent.platform.security.CredentialStoreException
import com.deepstudent.platform.security.DsbkError
import com.deepstudent.platform.security.DsbkException
import com.deepstudent.platform.security.DsbkFormat
import com.deepstudent.platform.security.SecretBytes
import com.deepstudent.platform.security.SecurityAudit
import com.deepstudent.platform.security.SecurityAuditEvent
import com.deepstudent.platform.security.SecurityFactory
import com.deepstudent.platform.security.SecurityOperation
import com.deepstudent.platform.security.SecurityOutcome
import com.deepstudent.platform.security.SecurityPhase
import com.deepstudent.platform.security.SecurityPorts
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainSecurityResourcesDeviceTest {
    private val id = UUID.randomUUID().toString()
    private val alias = "com.deepstudent.app.security.test.$id"
    private lateinit var root: File
    private lateinit var context: Context
    private lateinit var foreground: EditorTestHost

    @Before
    fun setUp() {
        foreground = EditorTestHost().also { it.start() }
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            foreground.activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        root = File(base.cacheDir.canonicalFile, "security-app-test-$id")
        val noBackup = File(root, "no-backup").apply { check(mkdirs()) }
        val cache = File(root, "cache").apply { check(mkdirs()) }
        context = object : ContextWrapper(base) {
            override fun getNoBackupFilesDir(): File = noBackup
            override fun getCacheDir(): File = cache
        }
    }

    @After
    fun tearDown() {
        // Only this test's UUID alias is ever removed; never use the production alias.
        check(alias.startsWith("com.deepstudent.app.security.test."))
        check(alias != AndroidKeystoreKeyWrapper.DEFAULT_ALIAS)
        try {
            AndroidKeystoreKeyWrapper(alias).deleteKey()
        } finally {
            if (::root.isInitialized) root.deleteRecursively()
            if (::foreground.isInitialized) foreground.close()
        }
    }

    @Test
    fun configuredFactoryEncryptsAndReopensCredentialWithoutLoggingSecrets() {
        val sentinel = "SECRET_SENTINEL_$id"
        val bytes = sentinel.toByteArray(Charsets.UTF_8)
        val passwordSentinel = "PASSWORD_SENTINEL_$id"
        val password = passwordSentinel.toCharArray()
        val key = CredentialKey.of(sentinel)
        val events = mutableListOf<SecurityAuditEvent>()
        val securityRoot = File(context.noBackupFilesDir, "security")
        val staging = File(context.cacheDir, "security-staging")

        openIsolated(events).use { ports ->
            assertTrue(securityRoot.isDirectory)
            assertTrue(staging.isDirectory)
            val value = SecretBytes.fromBytes(bytes)
            try {
                ports.credentials.put(key, value)
            } finally {
                value.close()
            }
            try {
                val output = ByteArrayOutputStream()
                ports.dsbk.encryptV2(ByteArrayInputStream(bytes), output, password)
                val encrypted = output.toByteArray()
                assertTrue(ports.dsbk.isEncryptedBackup(encrypted))
                val probe = ports.dsbk.preflight(ByteArrayInputStream(encrypted), encrypted.size.toLong())
                assertEquals(2, probe.version)
                assertEquals(bytes.size.toLong(), probe.plaintextBytes)
                assertFalse(contains(encrypted, bytes))
                assertArrayEquals(bytes, ports.dsbk.decryptToBytes(ByteArrayInputStream(encrypted), password, 1024))
            } finally {
                password.fill('\u0000')
            }
        }
        val record = File(securityRoot, "credentials").walkTopDown()
            .single { it.isFile && it.extension == "dsc" }
        assertFalse(contains(record.readBytes(), bytes))

        openIsolated(events).use { ports ->
            val value = ports.credentials.get(key)
            try {
                value.use { assertArrayEquals(bytes, it) }
            } finally {
                value.close()
            }
            val missing = assertThrows(CredentialStoreException::class.java) {
                ports.credentials.get(CredentialKey.of("missing_$sentinel"))
            }
            assertEquals(CredentialStoreError.NOT_FOUND, missing.error)
            assertFalse(missing.stackTraceToString().contains(sentinel))
        }
        assertTrue(events.isNotEmpty())
        assertTrue(events.any { it.outcome == SecurityOutcome.NOT_FOUND })
        assertTrue(events.any { it.operation == SecurityOperation.DSBK_ENCRYPT && it.outcome == SecurityOutcome.SUCCEEDED })
        assertTrue(events.any { it.operation == SecurityOperation.DSBK_DECRYPT && it.outcome == SecurityOutcome.SUCCEEDED })
        assertTrue(events.none { it.toString().contains(sentinel) || it.toString().contains(passwordSentinel) })
    }

    @Test
    fun swappedTruncatedAndWrongPasswordFilesNeverCommitOrLogTheirPayload() {
        val events = mutableListOf<SecurityAuditEvent>()
        val password = "PASSWORD_SENTINEL_$id".toCharArray()
        val wrong = "WRONG_SENTINEL_$id".toCharArray()
        try {
            openIsolated(events).use { ports ->
                val encrypted = multiChunkFixture(ports, password)
                val active = File(context.cacheDir.canonicalFile, "keep.bin").apply { writeText("prior active bytes") }
                verifyRejected(ports, active, swappedChunks(encrypted), password, "swapped")
                verifyRejected(ports, active, encrypted.copyOf(encrypted.size - 1), password, "truncated")
                verifyRejected(ports, active, encrypted, wrong, "wrong password")
                val traces = events.filter { it.operation == SecurityOperation.DSBK_DECRYPT }.groupBy { it.correlationId }
                assertEquals(3, traces.size)
                for ((correlation, trace) in traces) {
                    assertTrue(correlation?.matches(Regex("[0-9a-f]{32}")) == true)
                    assertEquals(SecurityPhase.DISPATCH, trace.first().phase)
                    assertEquals(SecurityPhase.V2_DECRYPT, trace.last().phase)
                    assertEquals(SecurityOutcome.FAILED, trace.last().outcome)
                    assertEquals(DsbkError.AUTHENTICATION_FAILED.name, trace.last().errorCode)
                    assertTrue(trace.none { it.phase == SecurityPhase.COMMIT })
                }
                assertTrue(events.none { it.toString().contains("_SENTINEL_") })
            }
        } finally {
            password.fill('\u0000')
            wrong.fill('\u0000')
        }
    }

    private fun multiChunkFixture(ports: SecurityPorts, password: CharArray): ByteArray {
        val bytes = ByteArray(2 * DsbkFormat.STREAM_CHUNK + 23) { (it % 191).toByte() }
        val output = ByteArrayOutputStream()
        try { ports.dsbk.encryptV2(bytes.inputStream(), output, password) }
        finally { bytes.fill(0) }
        return output.toByteArray()
    }

    private fun swappedChunks(encrypted: ByteArray): ByteArray {
        val chunk = DsbkFormat.STREAM_CHUNK + DsbkFormat.GCM_TAG_LEN
        val start = DsbkFormat.V2_HEADER_LEN
        return encrypted.copyOf().apply {
            encrypted.copyInto(this, start, start + chunk, start + 2 * chunk)
            encrypted.copyInto(this, start + chunk, start, start + chunk)
        }
    }

    private fun verifyRejected(ports: SecurityPorts, active: File, bytes: ByteArray, password: CharArray, fixture: String) {
        val sink = ports.staging.createSink(active)
        val error = assertThrows(DsbkException::class.java) {
            ports.dsbk.decrypt(bytes.inputStream(), password, sink, 3L * DsbkFormat.STREAM_CHUNK)
        }
        assertEquals(fixture, DsbkError.AUTHENTICATION_FAILED, error.error)
        assertEquals("prior active bytes", active.readText())
        assertTrue(File(context.cacheDir, "security-staging").listFiles().orEmpty().none { it.name.endsWith(".part") })
    }

    @Test
    fun unexpectedFactoryErrorDoesNotExposeMessageOrCause() {
        val sentinel = "FACTORY_SENTINEL_$id"
        val error = assertThrows(CredentialStoreException::class.java) {
            MainSecurityResources.open(context) { _, _ ->
                throw IOException("path $sentinel", IOException("cause $sentinel"))
            }
        }
        assertEquals(CredentialStoreError.STORAGE_UNAVAILABLE, error.error)
        assertNull(error.cause)
        assertFalse(error.stackTraceToString().contains(sentinel))
    }

    private fun openIsolated(events: MutableList<SecurityAuditEvent>): SecurityPorts =
        MainSecurityResources.open(context) { supplied, productionAudit ->
            assertSame(context, supplied)
            SecurityFactory.create(
                rootDirectory = File(supplied.noBackupFilesDir.canonicalFile, "security"),
                keystoreAlias = alias,
                stagingDirectory = File(supplied.cacheDir.canonicalFile, "security-staging"),
                audit = SecurityAudit { event ->
                    events += event
                    productionAudit.onEvent(event)
                },
            )
        }

    private fun contains(haystack: ByteArray, needle: ByteArray): Boolean =
        needle.isNotEmpty() && haystack.size >= needle.size &&
            (0..haystack.size - needle.size).any { start ->
                needle.indices.all { haystack[start + it] == needle[it] }
            }
}

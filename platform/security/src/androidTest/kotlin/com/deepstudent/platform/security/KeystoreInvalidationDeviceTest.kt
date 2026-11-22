package com.deepstudent.platform.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.SecretKey
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real AndroidKeyStore tests: no software wrapper and no substituted KDF. */
@RunWith(AndroidJUnit4::class)
class KeystoreInvalidationDeviceTest {
    private lateinit var root: File
    private lateinit var alias: String
    private lateinit var host: SecurityForegroundHost

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        root = File(context.cacheDir.canonicalFile, "security-invalidation-${UUID.randomUUID()}")
        check(root.mkdirs())
        alias = "${AndroidKeystoreKeyWrapper.DEFAULT_ALIAS}.${root.name}"
        host = SecurityForegroundHost().also { it.start() }
    }

    @After
    fun tearDown() {
        runCatching { AndroidKeystoreKeyWrapper(alias).deleteKey() }
        root.deleteRecursively()
        host.close()
    }

    @Test
    fun liveStoreObservesKeyLossWithoutRegeneratingAliasAndExplicitRebuildRecovers() {
        val ports = SecurityFactory.create(root, alias)
        val key = CredentialKey.of("live-device-credential")
        val value = SecretBytes.fromUtf8("device-private-sentinel".toCharArray())
        try {
            ports.credentials.put(key, value)
            ports.credentials.get(key).close()
            val master = File(root, "master.key")
            val before = master.readBytes()
            val record = File(root, "credentials").listFiles()!!.first { it.name.endsWith(".dsc") }
            val beforeRecord = record.readBytes()
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            assertNull((keyStore.getKey(alias, null) as SecretKey).encoded)
            AndroidKeystoreKeyWrapper(alias).deleteKey()
            assertEquals(CredentialStoreError.MASTER_KEY_UNAVAILABLE, expectCredential { ports.credentials.get(key) }.error)
            assertEquals(CredentialStoreError.MASTER_KEY_UNAVAILABLE, expectCredential { ports.credentials.put(key, value) }.error)
            assertFalse(keyStore.containsAlias(alias))
            assertArrayEquals(before, master.readBytes())
            assertArrayEquals(beforeRecord, record.readBytes())
            ports.rebuildMasterKey()
            assertTrue(keyStore.containsAlias(alias))
            assertEquals(CredentialStoreError.NOT_FOUND, expectCredential { ports.credentials.get(key) }.error)
            ports.credentials.put(key, value)
            val loaded = ports.credentials.get(key)
            try { loaded.use { assertArrayEquals("device-private-sentinel".toByteArray(), it) } }
            finally { loaded.close() }
        } finally {
            value.close()
            ports.close()
        }
    }

    @Test
    fun probeAndGuardedResetRequireProvenKeystoreLoss() {
        val events = mutableListOf<SecurityAuditEvent>()
        val ports = SecurityFactory.create(root, alias, audit = SecurityAudit { events.add(it) })
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val key = CredentialKey.of("guarded-device-credential")
        val value = SecretBytes.fromUtf8("private-device-value".toCharArray())
        try {
            ports.requireMasterKeyUsable()
            assertFalse(keyStore.containsAlias(alias))
            assertFalse(File(root, "master.key").exists())
            assertEquals(CredentialStoreError.MASTER_KEY_RESET_NOT_ALLOWED,
                expectCredential { ports.rebuildLostMasterKey() }.error)
            ports.credentials.put(key, value)
            val master = File(root, "master.key")
            val masterBefore = master.readBytes()
            val record = File(root, "credentials").listFiles()!!.single { it.name.endsWith(".dsc") }
            val recordBefore = record.readBytes()
            ports.requireMasterKeyUsable()
            assertEquals(CredentialStoreError.MASTER_KEY_RESET_NOT_ALLOWED,
                expectCredential { ports.rebuildLostMasterKey() }.error)
            assertArrayEquals(masterBefore, master.readBytes())
            assertArrayEquals(recordBefore, record.readBytes())
            AndroidKeystoreKeyWrapper(alias).deleteKey()
            val loss = expectCredential { ports.requireMasterKeyUsable() }
            assertEquals(CredentialStoreError.MASTER_KEY_UNAVAILABLE, loss.error)
            assertEquals(MasterKeyFailure.PERMANENTLY_LOST, loss.masterKeyFailure)
            assertFalse(keyStore.containsAlias(alias))
            assertArrayEquals(masterBefore, master.readBytes())
            assertArrayEquals(recordBefore, record.readBytes())
            ports.rebuildLostMasterKey()
            assertTrue(keyStore.containsAlias(alias))
            assertFalse(record.exists())
            ports.requireMasterKeyUsable()
            ports.credentials.put(key, value)
            val loaded = ports.credentials.get(key)
            try {
                loaded.use { assertArrayEquals("private-device-value".toByteArray(), it) }
            } finally {
                loaded.close()
            }
            val reset = events.filter { it.operation == SecurityOperation.MASTER_KEY_RESET }.takeLast(4)
            assertEquals(1, reset.map { it.correlationId }.distinct().size)
            assertEquals(SecurityPhase.RESET_APPLY, reset.last().phase)
            assertEquals(SecurityOutcome.SUCCEEDED, reset.last().outcome)
            assertFalse(events.joinToString().contains("private-device-value"))
        } finally {
            value.close()
            ports.close()
        }
    }

    @Test
    fun realGcmAuthenticationFailureIsClassifiedWithoutRewritingTheRecord() {
        val ports = SecurityFactory.create(root, alias)
        val key = CredentialKey.of("tamper-device-credential")
        val value = SecretBytes.fromUtf8("tamper-device-private".toCharArray())
        try {
            ports.credentials.put(key, value)
            val record = File(root, "credentials").listFiles()!!.single { it.name.endsWith(".dsc") }
            val before = record.readBytes()
            val master = File(root, "master.key")
            val tampered = master.readBytes().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
            master.writeBytes(tampered)
            val loss = expectCredential { ports.requireMasterKeyUsable() }
            assertEquals(CredentialStoreError.MASTER_KEY_UNAVAILABLE, loss.error)
            assertEquals(MasterKeyFailure.PERMANENTLY_LOST, loss.masterKeyFailure)
            assertArrayEquals(tampered, master.readBytes())
            assertArrayEquals(before, record.readBytes())
        } finally {
            value.close()
            ports.close()
        }
    }

    @Test
    fun concurrentWrapperInstancesDoNotReplaceEachOthersKeystoreKey() {
        val pool = Executors.newFixedThreadPool(2)
        val barrier = CyclicBarrier(2)
        val master = ByteArray(32) { (it * 7).toByte() }
        try {
            val futures = (1..2).map {
                pool.submit<ByteArray> {
                    barrier.await(10, TimeUnit.SECONDS)
                    AndroidKeystoreKeyWrapper(alias).wrap(master)
                }
            }
            for (future in futures) {
                val wrapped = future.get(30, TimeUnit.SECONDS)
                val unwrapped = AndroidKeystoreKeyWrapper(alias).unwrap(wrapped)
                try { assertArrayEquals(master, unwrapped) } finally { unwrapped.fill(0) }
            }
        } finally {
            master.fill(0)
            pool.shutdownNow()
        }
    }

    @Test
    fun realKeystoreRejectsTamperedWrappedMasterAndDoesNotRotateIt() {
        val wrapper = AndroidKeystoreKeyWrapper(alias)
        val file = File(root, "master.key")
        WrappedMasterKeyStore(file, wrapper).loadOrCreate().close()
        val bytes = file.readBytes().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        file.writeBytes(bytes)
        assertEquals(CredentialStoreError.MASTER_KEY_UNAVAILABLE,
            expectCredential { WrappedMasterKeyStore(file, AndroidKeystoreKeyWrapper(alias)).loadOrCreate() }.error)
        assertArrayEquals(bytes, file.readBytes())
    }

    @Test
    fun frozenDesktopProductionFixtureDecryptsWithActualDeviceKdf() {
        val context = InstrumentationRegistry.getInstrumentation().context
        val fixture = context.assets.open("security/dsbk-production-golden.json")
            .bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
        val password = fixture.getString("password").toCharArray()
        val plaintext = hex(fixture.getJSONObject("plaintext").getString("fullHex"))
        val derived = Argon2idKeyDeriver().derive(password, hex(fixture.getString("saltHex")), DsbkCrypto.PRODUCTION_KDF)
        try {
            assertEquals(fixture.getString("derivedKeySha256"), sha256(derived))
            for (version in listOf("v1", "v2")) {
                val encrypted = hex(fixture.getJSONObject(version).getString("fullHex"))
                val actual = DsbkCrypto.production().decryptToBytes(ByteArrayInputStream(encrypted), password, plaintext.size.toLong())
                try { assertArrayEquals(plaintext, actual) } finally { actual.fill(0) }
            }
        } finally {
            derived.fill(0)
            password.fill('\u0000')
            plaintext.fill(0)
        }
    }

    private fun expectCredential(block: () -> Any?): CredentialStoreException {
        try {
            block()
            fail("expected credential failure")
        } catch (error: CredentialStoreException) { return error }
        error("unreachable")
    }

    private fun hex(value: String): ByteArray = ByteArray(value.length / 2) {
        value.substring(2 * it, 2 * it + 2).toInt(16).toByte()
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

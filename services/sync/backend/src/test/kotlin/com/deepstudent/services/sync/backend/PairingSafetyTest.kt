package com.deepstudent.services.sync.backend

import com.deepstudent.services.sync.api.PairingMode
import com.deepstudent.services.sync.api.PairingRequest
import com.deepstudent.services.sync.api.SyncConfiguration
import com.deepstudent.services.sync.api.SyncError
import com.deepstudent.services.sync.api.SyncResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingSafetyTest {
    @Test fun futureMalformedEncryptedAndMissingRepositoryNeverSaveConfiguration() = runBlocking {
        val formats = listOf(
            """{"format_version":5,"min_client":4}""" to SyncError.INCOMPATIBLE_FORMAT,
            """{"format_version":4,"min_client":5}""" to SyncError.INCOMPATIBLE_FORMAT,
            """{"format_version":4,"features":[3]}""" to SyncError.INVALID_REPOSITORY,
            """{"min_client":3,"min_reader_version":3}""" to SyncError.INVALID_REPOSITORY,
            "DSBK-public" to SyncError.ENCRYPTED_REPOSITORY,
        )
        for ((format, error) in formats) {
            val memory = Memory()
            memory.seed(format)
            val secret = "public-password".toCharArray()
            assertEquals(SyncResult.Failure(error), memory.service().pair(PairingMode.JOIN, request(secret)))
            assertNull(memory.configuration)
            assertTrue(secret.all { it == '\u0000' })
        }
        val missing = Memory()
        assertEquals(SyncResult.Failure(SyncError.INVALID_REPOSITORY), missing.service().pair(PairingMode.JOIN, request()))
        missing.seed(PairingService.FORMAT)
        missing.objects[PairingService.ENCRYPTION_MARKER_KEY] = byteArrayOf(0)
        assertEquals(SyncResult.Failure(SyncError.ENCRYPTED_REPOSITORY), missing.service().pair(PairingMode.JOIN, request()))
    }

    @Test fun readbackMismatchAndConcurrentCreationCannotOverwriteOrSave() = runBlocking {
        val memory = Memory()
        memory.corruptReadback = true
        assertEquals(SyncResult.Failure(SyncError.INVALID_REPOSITORY), memory.service().pair(PairingMode.CREATE, request()))
        assertNull(memory.configuration)
        assertNull(memory.objects[PairingService.INSTANCE_KEY])
        val existing = memory.objects.getValue(PairingService.FORMAT_KEY).copyOf()
        memory.corruptReadback = false
        assertEquals(SyncResult.Failure(SyncError.ALREADY_EXISTS), memory.service().pair(PairingMode.CREATE, request()))
        assertTrue(existing.contentEquals(memory.objects.getValue(PairingService.FORMAT_KEY)))
        assertEquals(SyncResult.Failure(SyncError.INVALID_REPOSITORY), memory.service().pair(PairingMode.JOIN, request()))
    }

    @Test fun losingEncryptedCreateCannotMarkAPlainRepository() = runBlocking {
        val memory = Memory()
        val reachedClaim = CompletableDeferred<Unit>()
        val releaseClaim = CompletableDeferred<Unit>()
        val slowProvider = object : ObjectProvider by memory {
            override suspend fun put(key: String, value: ByteArray, ifAbsent: Boolean) {
                if (key == PairingService.FORMAT_KEY) { reachedClaim.complete(Unit); releaseClaim.await() }
                memory.put(key, value, ifAbsent)
            }
        }
        val slowFactory = object : ProviderFactory {
            override fun open(endpoint: String, username: String, root: String, password: CharArray) = slowProvider
        }
        val crypto = object : SyncCryptography {
            override fun createVerifier(password: CharArray) = SyncPasswordVerifier(64, 1, 1, "ab".repeat(8), "cd".repeat(32))
            override fun verify(password: CharArray, verifier: SyncPasswordVerifier) = error("not used")
            override fun payload(password: CharArray): SyncPayloadCodec = error("not used")
        }
        val encryptedRequest = request().copy(encryptionPassword = charArrayOf('x'))
        val contender = PairingService(memory, memory, slowFactory, encryption = RepositoryEncryption(crypto))
        val losing = async { contender.pair(PairingMode.CREATE, encryptedRequest) }
        withTimeout(5_000) { reachedClaim.await() }
        try {
            assertTrue(memory.service().pair(PairingMode.CREATE, request()) is SyncResult.Success)
        } finally { releaseClaim.complete(Unit) }
        assertEquals(SyncResult.Failure(SyncError.ALREADY_EXISTS), losing.await())
        assertNull(memory.objects[PairingService.ENCRYPTION_MARKER_KEY])
        assertTrue(memory.service().pair(PairingMode.JOIN, request()) is SyncResult.Success)
    }

    @Test fun cancelledPairingClearsOwnedSecretAndDoesNotReplaceConfiguration() = runBlocking {
        val memory = Memory()
        val entered = CompletableDeferred<Unit>()
        val blocked = CompletableDeferred<Unit>()
        val factory = object : ProviderFactory {
            override fun open(endpoint: String, username: String, root: String, password: CharArray): ObjectProvider =
                object : ObjectProvider by memory {
                    override suspend fun get(key: String): ByteArray? { entered.complete(Unit); blocked.await(); return null }
                }
        }
        val secret = "public-password".toCharArray()
        val job = launch { PairingService(memory, memory, factory).pair(PairingMode.JOIN, request(secret)) }
        entered.await()
        job.cancelAndJoin()
        assertTrue(secret.all { it == '\u0000' })
        assertNull(memory.configuration)
    }

    @Test fun legacyV3JoinAndCredentialErrorsRemainTyped() = runBlocking {
        val memory = Memory()
        memory.seed(null)
        memory.failure = SyncError.CREDENTIAL_UNAVAILABLE
        assertEquals(SyncResult.Failure(SyncError.CREDENTIAL_UNAVAILABLE), memory.service().pair(PairingMode.JOIN, request()))
        memory.failure = null
        assertTrue(memory.service().pair(PairingMode.JOIN, request()) is SyncResult.Success)
        assertEquals("independent-android", memory.configuration?.deviceId)
    }

    private fun request(password: CharArray = charArrayOf()) = PairingRequest("https://public.invalid/dav", "public", "test", password)

    private class Memory : ObjectProvider, ConfigurationStore, DeviceIdentity, ProviderFactory {
        val objects = mutableMapOf<String, ByteArray>()
        var configuration: SyncConfiguration? = null
        var corruptReadback = false
        var failure: SyncError? = null
        fun service() = PairingService(this, this, this)
        fun seed(format: String?) {
            objects[PairingService.INSTANCE_KEY] = """{"instance_id":"public-repository","format_version":3}""".toByteArray()
            format?.let { objects[PairingService.FORMAT_KEY] = it.toByteArray() }
        }
        override suspend fun get(key: String): ByteArray? = objects[key]?.let {
            if (corruptReadback) "{}".toByteArray() else it.copyOf()
        }
        override suspend fun put(key: String, value: ByteArray, ifAbsent: Boolean) {
            if (ifAbsent && objects.containsKey(key)) throw SyncFailure(SyncError.ALREADY_EXISTS)
            objects[key] = value.copyOf()
        }
        override suspend fun list(prefix: String): List<String> = objects.keys.filter { it.startsWith(prefix) }
        override suspend fun delete(key: String) { objects.remove(key) }
        override fun read(): SyncConfiguration? = configuration
        override fun save(configuration: SyncConfiguration, password: CharArray, encryptionPassword: CharArray?) {
            failure?.let { throw SyncFailure(it) }
            this.configuration = configuration
        }
        override fun replaceEncryptionPassword(configuration: SyncConfiguration, password: CharArray) = error("not used")
        override fun clear() { configuration = null }
        override fun getOrCreate(): String = "independent-android"
        override fun open(endpoint: String, username: String, root: String, password: CharArray): ObjectProvider = this
    }
}

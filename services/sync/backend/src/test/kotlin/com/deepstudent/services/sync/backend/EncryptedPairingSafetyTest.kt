package com.deepstudent.services.sync.backend

import com.deepstudent.services.sync.api.PairingMode
import com.deepstudent.services.sync.api.PairingRequest
import com.deepstudent.services.sync.api.SyncConfiguration
import com.deepstudent.services.sync.api.SyncError
import com.deepstudent.services.sync.api.SyncResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

/** Tests the authorization boundary; real Argon2/DSBK interoperability is tested in sync/data. */
class EncryptedPairingSafetyTest {
    @Test fun wrongPasswordAndMalformedMarkerCannotReplaceSavedConfigurationOrWriteRemote() = runBlocking {
        val memory = Memory()
        val service = memory.service()
        val create = request("correct")
        val created = service.pair(PairingMode.CREATE, create) as SyncResult.Success
        assertTrue(created.value.encrypted)
        assertCleared(create)
        val originalConfig = memory.config
        val originalCredential = memory.encryptionPassword
        val originalObjects = memory.snapshot()
        val saves = memory.saves
        val puts = memory.puts

        val wrong = request("wrong")
        assertEquals(SyncResult.Failure(SyncError.ENCRYPTION_PASSWORD_INCORRECT), service.pair(PairingMode.JOIN, wrong))
        assertCleared(wrong)
        assertEquals(originalConfig, memory.config)
        assertEquals(originalCredential, memory.encryptionPassword)
        assertEquals(saves, memory.saves)
        assertEquals(puts, memory.puts)
        memory.assertSnapshot(originalObjects)

        val markerKey = PairingService.ENCRYPTION_MARKER_KEY
        val marker = memory.objects.getValue(markerKey).decodeToString()
        val malformedMarkers = listOf(
            "not-json",
            marker.replace("\"version\":2", "\"version\":3"),
            marker.replace("\"argon2id\"", "\"unknown-kdf\""),
            marker.replace("\"salt\":\"0011223344556677\"", "\"salt\":\"not-hex\""),
            marker.replace(Regex("\"digest\":\"[0-9a-f]+\""), "\"digest\":\"00\""),
        )
        for (bad in malformedMarkers) {
            assertFalse("fixture must actually alter the marker", bad == marker)
            memory.objects[markerKey] = bad.toByteArray()
            val before = memory.snapshot()
            val attempted = request("correct")
            assertEquals(SyncResult.Failure(SyncError.INVALID_ENCRYPTION_MARKER), service.pair(PairingMode.JOIN, attempted))
            assertCleared(attempted)
            assertEquals(originalConfig, memory.config)
            assertEquals(originalCredential, memory.encryptionPassword)
            assertEquals(saves, memory.saves)
            assertEquals(puts, memory.puts)
            memory.assertSnapshot(before)
        }
        // A well-formed but changed verifier is an authentication failure, not permission to join.
        val changedDigest = marker.replace(Regex("\"digest\":\"[0-9a-f]+\""), "\"digest\":\"" + "00".repeat(32) + "\"")
        assertFalse(changedDigest == marker)
        memory.objects[markerKey] = changedDigest.toByteArray()
        val altered = memory.snapshot()
        val alteredRequest = request("correct")
        assertEquals(SyncResult.Failure(SyncError.ENCRYPTION_PASSWORD_INCORRECT), service.pair(PairingMode.JOIN, alteredRequest))
        assertCleared(alteredRequest)
        assertEquals(originalConfig, memory.config)
        assertEquals(originalCredential, memory.encryptionPassword)
        assertEquals(saves, memory.saves)
        assertEquals(puts, memory.puts)
        memory.assertSnapshot(altered)

        memory.objects[markerKey] = originalObjects.getValue(markerKey).copyOf()
        val valid = request("correct")
        assertTrue(created.value.requiresInitialSeed)
        assertEquals(created.value, (service.pair(PairingMode.JOIN, valid) as SyncResult.Success).value)
        assertCleared(valid)
    }

    @Test fun updatingPasswordVerifiesExistingMarkerAndInstanceBeforeReplacingSecret() = runBlocking {
        val memory = Memory()
        val service = memory.service()
        service.pair(PairingMode.CREATE, request("correct")) as SyncResult.Success
        val before = memory.snapshot()
        val originalConfig = memory.config
        val wrong = "wrong".toCharArray()
        assertEquals(SyncResult.Failure(SyncError.ENCRYPTION_PASSWORD_INCORRECT), service.updateEncryptionPassword(wrong))
        assertTrue(wrong.all { it == '\u0000' })
        assertEquals("correct", memory.encryptionPassword)
        assertEquals(0, memory.replacements)
        assertEquals(originalConfig, memory.config)
        memory.assertSnapshot(before)

        val markerKey = PairingService.ENCRYPTION_MARKER_KEY
        memory.objects[markerKey] = "{}".toByteArray()
        val tampered = memory.snapshot()
        val correct = "correct".toCharArray()
        assertEquals(SyncResult.Failure(SyncError.INVALID_ENCRYPTION_MARKER), service.updateEncryptionPassword(correct))
        assertTrue(correct.all { it == '\u0000' })
        assertEquals("correct", memory.encryptionPassword)
        assertEquals(0, memory.replacements)
        memory.assertSnapshot(tampered)

        memory.objects[markerKey] = before.getValue(markerKey).copyOf()
        memory.objects[PairingService.INSTANCE_KEY] = """{"instance_id":"different-repository","format_version":4}""".toByteArray()
        val wrongInstance = memory.snapshot()
        val correctForOtherInstance = "correct".toCharArray()
        assertEquals(SyncResult.Failure(SyncError.INVALID_REPOSITORY), service.updateEncryptionPassword(correctForOtherInstance))
        assertTrue(correctForOtherInstance.all { it == '\u0000' })
        assertEquals("correct", memory.encryptionPassword)
        assertEquals(0, memory.replacements)
        memory.assertSnapshot(wrongInstance)
        memory.objects[PairingService.INSTANCE_KEY] = before.getValue(PairingService.INSTANCE_KEY).copyOf()
        val accepted = "correct".toCharArray()
        assertEquals(SyncResult.Success(Unit), service.updateEncryptionPassword(accepted))
        assertTrue(accepted.all { it == '\u0000' })
        assertEquals(1, memory.replacements)
        memory.assertSnapshot(before)
    }

    @Test fun markerChangedDuringJoinAndBeforeGuardedWriteFailClosed() = runBlocking {
        val memory = Memory()
        val service = memory.service()
        service.pair(PairingMode.CREATE, request("correct")) as SyncResult.Success
        val before = memory.snapshot()
        val config = memory.config
        val saves = memory.saves
        val puts = memory.puts
        memory.changeMarkerWhenFormatRead = true
        val attempted = request("correct")
        assertEquals(SyncResult.Failure(SyncError.INVALID_ENCRYPTION_MARKER), service.pair(PairingMode.JOIN, attempted))
        assertCleared(attempted)
        assertEquals(config, memory.config)
        assertEquals(saves, memory.saves)
        assertEquals(puts, memory.puts)
        assertArrayEquals(before.getValue(PairingService.INSTANCE_KEY), memory.objects.getValue(PairingService.INSTANCE_KEY))
        assertArrayEquals(before.getValue(PairingService.FORMAT_KEY), memory.objects.getValue(PairingService.FORMAT_KEY))

        memory.objects[PairingService.ENCRYPTION_MARKER_KEY] = before.getValue(PairingService.ENCRYPTION_MARKER_KEY).copyOf()
        val policy = RepositoryEncryption(FakeCrypto()).check(memory, true, "correct".toCharArray())
        val target = "data_governance/manifests/existing.json"
        memory.objects[target] = "existing".toByteArray()
        memory.objects.remove(PairingService.ENCRYPTION_MARKER_KEY)
        val failure = assertThrows(SyncFailure::class.java) {
            runBlocking { policy.guardWrites(memory).put(target, "replacement".toByteArray()) }
        }
        assertEquals(SyncError.INVALID_ENCRYPTION_MARKER, failure.error)
        assertEquals(puts, memory.puts)
        assertArrayEquals("existing".toByteArray(), memory.objects.getValue(target))

        val plainPolicy = RepositoryEncryption().check(memory, false, null)
        memory.objects[PairingService.ENCRYPTION_MARKER_KEY] = before.getValue(PairingService.ENCRYPTION_MARKER_KEY).copyOf()
        val modeFailure = assertThrows(SyncFailure::class.java) {
            runBlocking { plainPolicy.guardWrites(memory).put(target, "replacement".toByteArray()) }
        }
        assertEquals(SyncError.ENCRYPTED_REPOSITORY, modeFailure.error)
        assertEquals(puts, memory.puts)
        assertArrayEquals("existing".toByteArray(), memory.objects.getValue(target))
    }

    private fun request(encryption: String) = PairingRequest(
        "https://fixture.invalid/dav",
        "fixture",
        "repository",
        "webdav".toCharArray(),
        encryption.toCharArray(),
    )

    private fun assertCleared(request: PairingRequest) {
        assertTrue(request.password.all { it == '\u0000' })
        assertNotNull(request.encryptionPassword)
        assertTrue(request.encryptionPassword!!.all { it == '\u0000' })
    }

    private class FakeCrypto : SyncCryptography {
        override fun createVerifier(password: CharArray) = SyncPasswordVerifier(64, 1, 1, "0011223344556677", digest(password))
        override fun verify(password: CharArray, verifier: SyncPasswordVerifier) = digest(password) == verifier.digestHex
        override fun payload(password: CharArray): SyncPayloadCodec = error("payload is outside this authorization test")
        private fun digest(password: CharArray): String = MessageDigest.getInstance("SHA-256")
            .digest(String(password).toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private class Memory :
        ObjectProvider,
        ConfigurationStore,
        DeviceIdentity,
        ProviderFactory {
        val objects = linkedMapOf<String, ByteArray>()
        var config: SyncConfiguration? = null
        var encryptionPassword: String? = null
        var saves = 0
        var replacements = 0
        var puts = 0
        var changeMarkerWhenFormatRead = false
        fun service() = PairingService(this, this, this, encryption = RepositoryEncryption(FakeCrypto()), openStored = { this })
        fun snapshot() = objects.mapValues { it.value.copyOf() }
        fun assertSnapshot(snapshot: Map<String, ByteArray>) {
            assertEquals(snapshot.keys, objects.keys)
            snapshot.forEach { (key, bytes) -> assertArrayEquals(key, bytes, objects[key]) }
        }
        override suspend fun get(key: String): ByteArray? {
            if (key == PairingService.FORMAT_KEY && changeMarkerWhenFormatRead) {
                changeMarkerWhenFormatRead = false
                objects[PairingService.ENCRYPTION_MARKER_KEY] = "externally changed".toByteArray()
            }
            return objects[key]?.copyOf()
        }
        override suspend fun put(key: String, value: ByteArray, ifAbsent: Boolean) {
            if (ifAbsent && key in objects) throw SyncFailure(SyncError.ALREADY_EXISTS)
            puts++
            objects[key] = value.copyOf()
        }
        override suspend fun list(prefix: String) = objects.keys.filter { it.startsWith(prefix) }
        override suspend fun delete(key: String) {
            objects.remove(key)
        }
        override fun read() = config
        override fun save(configuration: SyncConfiguration, password: CharArray, encryptionPassword: CharArray?) {
            saves++
            config = configuration
            this.encryptionPassword = encryptionPassword?.concatToString()
        }
        override fun replaceEncryptionPassword(configuration: SyncConfiguration, password: CharArray) {
            check(configuration == config)
            replacements++
            encryptionPassword = password.concatToString()
        }
        override fun clear() {
            config = null
            encryptionPassword = null
        }
        override fun getOrCreate() = "fixture-device"
        override fun open(endpoint: String, username: String, root: String, password: CharArray) = this
    }
}

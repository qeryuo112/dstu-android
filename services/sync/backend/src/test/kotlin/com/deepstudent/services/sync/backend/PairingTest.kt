package com.deepstudent.services.sync.backend

import com.deepstudent.services.sync.api.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PairingTest {
    private class Memory : ObjectProvider, ConfigurationStore, DeviceIdentity, ProviderFactory {
        val objects = mutableMapOf<String, ByteArray>()
        var config: SyncConfiguration? = null
        override suspend fun get(key: String) = objects[key]
        override suspend fun put(key: String, value: ByteArray, ifAbsent: Boolean) { if (ifAbsent && objects.containsKey(key)) throw SyncFailure(SyncError.ALREADY_EXISTS); objects[key] = value }
        override suspend fun list(prefix: String) = objects.keys.filter { it.startsWith(prefix) }
        override suspend fun delete(key: String) { objects.remove(key) }
        override fun read() = config
        override fun save(configuration: SyncConfiguration, password: CharArray, encryptionPassword: CharArray?) { config = configuration }
        override fun replaceEncryptionPassword(configuration: SyncConfiguration, password: CharArray) = error("not used")
        override fun clear() { config = null }
        override fun getOrCreate() = "device"
        override fun open(endpoint: String, username: String, root: String, password: CharArray) = this
    }

    @Test fun createJoinAndSecretOwnership() = runBlocking {
        val memory = Memory()
        val service = PairingService(memory, memory, memory)
        val password = "secret".toCharArray()
        val created = service.pair(PairingMode.CREATE, PairingRequest("https://example.com", "u", "r", password))
        assertTrue(created is SyncResult.Success)
        assertTrue(password.all { it == '\u0000' })
        val joined = service.pair(PairingMode.JOIN, PairingRequest("https://example.com", "u", "r", charArrayOf()))
        assertEquals(created, joined)
        assertEquals(SyncResult.Failure(SyncError.ALREADY_EXISTS), service.pair(PairingMode.CREATE, PairingRequest("", "", "", charArrayOf())))
        memory.objects[".encryption-marker"] = byteArrayOf(1)
        assertEquals(SyncResult.Failure(SyncError.ENCRYPTED_REPOSITORY), service.pair(PairingMode.JOIN, PairingRequest("", "", "", charArrayOf())))
    }

    @Test fun goldenChangeAndKey() {
        val input = "{\"table_name\":\"notes\",\"record_id\":\"n1\",\"operation\":\"INSERT\",\"data\":null,\"changed_at\":\"2026-11-15T00:00:00.123456789Z\"}"
        assertEquals(input.replace("INSERT", "Insert"), SyncWireCodec.encodeChange(input.toByteArray()).decodeToString())
        assertEquals("03204de92e11fc8c", SyncWireCodec.deviceHash("device-1"))
    }
}

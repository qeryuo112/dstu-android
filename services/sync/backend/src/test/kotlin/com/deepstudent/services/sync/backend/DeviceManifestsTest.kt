package com.deepstudent.services.sync.backend

import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.services.sync.api.SyncError
import com.deepstudent.services.sync.backend.transfer.DeviceManifests
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DeviceManifestsTest {
    @Test fun verifiedPublicationAndSchemaGateReadRealWireShape() = runBlocking {
        val provider = Provider()
        val manifests = DeviceManifests(provider)
        manifests.publish(manifest())
        assertEquals(setOf("peer"), manifests.read().keys)
        manifests.validateSchemas(mapOf("vfs" to 20261117))
        val failure = assertThrows(SyncFailure::class.java) {
            runBlocking { manifests.validateSchemas(mapOf("vfs" to 20261116)) }
        }
        assertEquals(SyncError.INCOMPATIBLE_FORMAT, failure.error)
    }

    @Test fun disappearingAndAliasedManifestCannotBeSilentlyAcknowledged(): Unit = runBlocking {
        val provider = Provider()
        val encoded = SyncManifestCodec.encode(manifest())
        provider.objects["data_governance/manifests/wrong.json"] = encoded
        assertThrows(SyncFailure::class.java) { runBlocking { DeviceManifests(provider).read() } }
        provider.objects.clear()
        provider.missing = true
        assertThrows(SyncFailure::class.java) { runBlocking { DeviceManifests(provider).read() } }
    }

    private fun manifest(): JsonValue.Object = JsonValue.parseObject("""{
        "sync_transaction_id":"public-tx","databases":{"vfs":{"schema_version":20261117,"data_version":0,"checksum":"public"}},
        "status":"Complete","created_at":"2026-11-18T00:00:00Z","device_id":"peer","format_version":4,
        "published_max_seq":1,"cursors":{},"snapshot_seen":{}}
    """)

    private class Provider : ObjectProvider {
        val objects = mutableMapOf<String, ByteArray>()
        var missing = false
        override suspend fun get(key: String): ByteArray? = objects[key]
        override suspend fun put(key: String, value: ByteArray, ifAbsent: Boolean) { objects[key] = value.copyOf() }
        override suspend fun list(prefix: String): List<String> = if (missing) listOf("data_governance/manifests/peer.json")
            else objects.keys.filter { it.startsWith(prefix) }
        override suspend fun delete(key: String) { objects.remove(key) }
    }
}

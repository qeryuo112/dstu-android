package com.deepstudent.services.sync.backend.transfer

import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.services.sync.api.SyncError
import com.deepstudent.services.sync.backend.ObjectProvider
import com.deepstudent.services.sync.backend.PlainSyncPayload
import com.deepstudent.services.sync.backend.SyncFailure
import com.deepstudent.services.sync.backend.SyncManifestCodec
import com.deepstudent.services.sync.backend.SyncPayloadCodec
import com.deepstudent.services.sync.backend.SyncWireCodec

class DeviceManifests(private val provider: ObjectProvider, private val codec: SyncPayloadCodec = PlainSyncPayload) {
    suspend fun read(): Map<String, JsonValue.Object> = read(includeSuperseded = false)

    internal suspend fun read(includeSuperseded: Boolean): Map<String, JsonValue.Object> {
        val keys = provider.list("data_governance/manifests")
        val result = mutableMapOf<String, JsonValue.Object>()
        for (key in keys) {
            if (!key.endsWith(".json")) continue
            val (id, manifest) = readManifest(key)
            if (!includeSuperseded && manifest["superseded_by"] != JsonValue.Null) continue
            val previous = result[id]
            if (previous != null && previous != manifest) invalid()
            result[id] = manifest
        }
        return result
    }

    private suspend fun readManifest(key: String): Pair<String, JsonValue.Object> {
        val bytes = provider.get(key) ?: invalid()
        return decodeForKey(key, codec.decode(bytes))
    }

    suspend fun validateSchemas(schemas: Map<String, Int>) {
        for (manifest in read().values) {
            val databases = manifest["databases"] as? JsonValue.Object ?: invalid()
            for ((name, entry) in databases.asMap()) {
                val schema = ((entry as? JsonValue.Object)?.get("schema_version") as? JsonValue.Number)?.asLongOrNull() ?: invalid()
                if (schema > (schemas[name] ?: 0)) throw SyncFailure(SyncError.INCOMPATIBLE_FORMAT)
            }
        }
    }

    suspend fun publish(manifest: JsonValue.Object) {
        val id = (manifest["device_id"] as? JsonValue.Text)?.value ?: invalid()
        val bytes = codec.encode(SyncManifestCodec.encode(manifest))
        val key = SyncWireCodec.manifestKey(id)
        provider.put(key, bytes)
        if (provider.get(key)?.contentEquals(bytes) != true) invalid()
    }

    companion object {
        internal fun decodeForKey(key: String, bytes: ByteArray): Pair<String, JsonValue.Object> {
            val manifest = SyncManifestCodec.decode(bytes)
            val id = (manifest["device_id"] as? JsonValue.Text)?.value ?: invalid()
            if (key != SyncWireCodec.manifestKey(id) && key != "data_governance/manifests/$id.json") invalid()
            return id to manifest
        }

        private fun invalid(): Nothing = throw SyncFailure(SyncError.INVALID_REPOSITORY)
    }
}

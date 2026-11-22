package com.deepstudent.services.sync.backend.transfer

import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.services.sync.backend.ObjectProvider
import com.deepstudent.services.sync.backend.SyncPayloadCodec
import com.deepstudent.services.sync.api.SyncError
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal class BlobTombstones(private val provider: ObjectProvider, private val codec: SyncPayloadCodec) {
    suspend fun read(): Map<String, String> {
        val merged = mutableMapOf<String, String>()
        provider.blobGet(BLOB_LEGACY_TOMBSTONE)?.let { merge(merged, decode(it)) }
        val keys = provider.blobList(BLOB_TOMBSTONE_PREFIX).sorted()
        for (key in keys.filter { it.startsWith(BLOB_TOMBSTONE_PREFIX) && it.endsWith(".json") }) {
            merge(merged, decode(provider.blobGet(key) ?: blobFailure(SyncError.NOT_FOUND)))
        }
        val events = provider.blobList(BLOB_EVENT_PREFIX)
        if (events.any { it.startsWith(BLOB_EVENT_PREFIX) && it.endsWith(".json") }) {
            blobFailure(SyncError.INCOMPATIBLE_FORMAT)
        }
        return merged
    }

    private fun decode(bytes: ByteArray): Map<String, String> {
        val root = blobJson(codec.decode(bytes))
        val entries = root["entries"]?.objectOrFail() ?: JsonValue.Object.EMPTY
        root["updated_at"]?.textOrFail()
        return entries.asMap().mapValues { (hash, raw) ->
            if (!Regex("[0-9a-f]{64}").matches(hash)) blobFailure()
            val entry = raw.objectOrFail()
            val deleted = entry["deleted_at"].textOrFail()
            entry["device_id"].textOrFail()
            entry["size"]?.takeUnless { it == JsonValue.Null }?.sizeOrFail()
            entry["relative_path"].optionalText()
            if (parseBlobTime(deleted) == null) blobFailure()
            deleted
        }
    }

    private fun merge(result: MutableMap<String, String>, incoming: Map<String, String>) {
        for ((hash, timestamp) in incoming) {
            val previous = result[hash]
            if (previous == null || newerBlobTime(timestamp, previous)) result[hash] = timestamp
        }
    }
}

internal suspend fun ObjectProvider.blobGet(key: String): ByteArray? {
    currentCoroutineContext().ensureActive()
    val value = get(key)
    currentCoroutineContext().ensureActive()
    return value
}

internal suspend fun ObjectProvider.blobList(prefix: String): List<String> {
    currentCoroutineContext().ensureActive()
    val value = list(prefix)
    currentCoroutineContext().ensureActive()
    return value
}

internal suspend fun ObjectProvider.blobPut(key: String, bytes: ByteArray, ifAbsent: Boolean = false) {
    currentCoroutineContext().ensureActive()
    put(key, bytes, ifAbsent)
    currentCoroutineContext().ensureActive()
}

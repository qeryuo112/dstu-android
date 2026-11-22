package com.deepstudent.services.sync.backend.transfer

import com.deepstudent.services.sync.api.SyncError
import com.deepstudent.services.sync.backend.ObjectProvider
import com.deepstudent.services.sync.backend.PlainSyncPayload
import com.deepstudent.services.sync.backend.SyncFailure
import com.deepstudent.services.sync.backend.SyncPayloadCodec
import com.deepstudent.core.common.id.Digests
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class BlobTransfer(private val provider: ObjectProvider, private val blobs: SyncBlobs, private val codec: SyncPayloadCodec = PlainSyncPayload) {
    suspend fun synchronize() {
        val tombstones = BlobTombstones(provider, codec).read()
        val remote = readManifests()
        val local = localBlobs()
        download(remote, local, tombstones)
        val uploaded = upload(remote, local, tombstones)
        if (uploaded.isNotEmpty()) publishUploaded(uploaded)
    }

    private suspend fun localBlobs(): Map<String, SyncBlob> {
        currentCoroutineContext().ensureActive()
        val list = blobs.list()
        currentCoroutineContext().ensureActive()
        val result = mutableMapOf<String, SyncBlob>()
        for (blob in list) {
            validateBlob(blob)
            if (result.put(blob.hash, blob) != null) blobFailure()
        }
        return result
    }

    private suspend fun download(remote: Map<String, BlobManifestEntry>, local: Map<String, SyncBlob>, deleted: Map<String, String>) {
        for ((hash, entry) in remote) {
            val (blob, cipher) = entry
            if (suppressed(blob, deleted)) continue
            val existing = local[hash]
            if (existing != null && readLocal(existing) != null) continue
            val wire = provider.blobGet(blobKey(blob)) ?: blobFailure(SyncError.NOT_FOUND)
            cipher?.verify(wire)
            val bytes = codec.decode(wire, blob = true)
            verifyBlob(blob, bytes)
            currentCoroutineContext().ensureActive()
            blobs.install(blob, bytes)
            currentCoroutineContext().ensureActive()
        }
    }

    private suspend fun upload(remote: Map<String, BlobManifestEntry>, local: Map<String, SyncBlob>, deleted: Map<String, String>): Map<String, BlobManifestEntry> {
        val uploaded = mutableMapOf<String, BlobManifestEntry>()
        for ((hash, blob) in local) {
            if (suppressed(blob, deleted)) continue
            val existing = remote[hash]?.blob
            if (existing != null && !suppressed(existing, deleted)) continue
            val bytes = readLocal(blob) ?: blobFailure(SyncError.NOT_FOUND)
            val cipher = publishBlob(blob, bytes)
            uploaded[hash] = BlobManifestEntry(blob, cipher)
        }
        return uploaded
    }

    private suspend fun publishBlob(blob: SyncBlob, bytes: ByteArray): CipherBlob? {
        val key = blobKey(blob)
        if (!codec.encrypted) {
            provider.blobPut(key, bytes)
            if (provider.blobGet(key)?.contentEquals(bytes) != true) blobFailure(SyncError.NETWORK)
            return null
        }
        val existing = provider.blobGet(key)
        if (existing != null) return verifyEncryptedBlob(blob, existing)
        return writeEncryptedBlob(blob, key, bytes)
    }

    private fun verifyEncryptedBlob(blob: SyncBlob, observed: ByteArray): CipherBlob {
        // An earlier attempt (or another device) may have placed these immutable bytes.
        // Re-encryption would change the hash and invalidate already published manifests.
        val plaintext = codec.decode(observed, blob = true)
        try { verifyBlob(blob, plaintext) } finally { plaintext.fill(0) }
        return CipherBlob(Digests.sha256Hex(observed), observed.size.toLong())
    }

    private suspend fun writeEncryptedBlob(blob: SyncBlob, key: String, bytes: ByteArray): CipherBlob {
        val wire = codec.encode(bytes, blob = true)
        try { provider.blobPut(key, wire, ifAbsent = true) }
        catch (failure: SyncFailure) { if (failure.error != SyncError.ALREADY_EXISTS) throw failure }
        val observed = provider.blobGet(key) ?: blobFailure(SyncError.NETWORK)
        return if (observed.contentEquals(wire)) CipherBlob(Digests.sha256Hex(wire), wire.size.toLong())
        else verifyEncryptedBlob(blob, observed)
    }

    private suspend fun readLocal(blob: SyncBlob): ByteArray? {
        currentCoroutineContext().ensureActive()
        val bytes = blobs.read(blob)
        currentCoroutineContext().ensureActive()
        if (bytes != null) verifyBlob(blob, bytes)
        return bytes
    }

    private suspend fun readManifests(): Map<String, BlobManifestEntry> {
        val result = mutableMapOf<String, BlobManifestEntry>()
        provider.blobGet(BLOB_LEGACY_MANIFEST)?.let { merge(result, decodeManifest(it)) }
        val keys = provider.blobList(BLOB_MANIFEST_PREFIX).sorted()
        for (key in keys.filter { it.startsWith(BLOB_MANIFEST_PREFIX) && it.endsWith(".json") }) {
            merge(result, decodeManifest(provider.blobGet(key) ?: blobFailure(SyncError.NOT_FOUND)))
        }
        return result
    }

    private fun decodeManifest(bytes: ByteArray): Map<String, BlobManifestEntry> =
        BlobManifestCodec.decode(codec.decode(bytes)).onEach { (_, entry) ->
            if (codec.encrypted && entry.cipher == null) blobFailure(SyncError.ENCRYPTION_MODE_MISMATCH)
            if (!codec.encrypted && entry.cipher != null) blobFailure(SyncError.ENCRYPTED_REPOSITORY)
        }

    private fun merge(result: MutableMap<String, BlobManifestEntry>, incoming: Map<String, BlobManifestEntry>) {
        for ((hash, entry) in incoming) {
            val existing = result[hash]?.blob
            if (existing == null || newerBlobTime(entry.blob.updatedAt, existing.updatedAt)) result[hash] = entry
        }
    }

    private suspend fun publishUploaded(uploaded: Map<String, BlobManifestEntry>) {
        val latest = readManifests().toMutableMap()
        val deleted = BlobTombstones(provider, codec).read()
        for ((hash, entry) in uploaded) {
            if (suppressed(entry.blob, deleted)) continue
            val old = latest[hash]?.blob
            if (old == null || suppressed(old, deleted)) latest[hash] = entry
        }
        val body = codec.encode(BlobManifestCodec.encode(latest, Instant.now().toString()))
        val key = "$BLOB_MANIFEST_PREFIX${UUID.randomUUID()}.json"
        provider.blobPut(key, body, ifAbsent = true)
        if (provider.blobGet(key)?.contentEquals(body) != true) blobFailure(SyncError.NETWORK)
    }
}

private fun blobKey(blob: SyncBlob): String = "data_governance/blobs/${blob.relativePath}"
private fun suppressed(blob: SyncBlob, deleted: Map<String, String>): Boolean =
    deleted[blob.hash]?.let { !newerBlobTime(blob.updatedAt, it) } ?: false

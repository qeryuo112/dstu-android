package com.deepstudent.services.sync.backend.transfer

import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.services.sync.api.DomainSyncAdapter
import com.deepstudent.services.sync.api.SyncError
import com.deepstudent.services.sync.backend.ObjectProvider
import com.deepstudent.services.sync.backend.PlainSyncPayload
import com.deepstudent.services.sync.backend.SyncBatch
import com.deepstudent.services.sync.backend.SyncBatchCodec
import com.deepstudent.services.sync.backend.SyncFailure
import com.deepstudent.services.sync.backend.SyncPayloadCodec
import com.deepstudent.services.sync.backend.SyncWireCodec
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class ChangeTransfer(
    private val provider: ObjectProvider,
    private val compression: SyncCompression,
    private val state: SyncUploadState,
    private val adapter: DomainSyncAdapter,
    private val deviceId: String,
    private val codec: SyncPayloadCodec = PlainSyncPayload,
) {
    suspend fun upload(minReader: Int) {
        if (minReader !in 3..4) throw SyncFailure(SyncError.INCOMPATIBLE_FORMAT)
        val journal = state as? PublicationJournal
        val reserved = journal?.pending()
        val pending = reserved ?: prepare(minReader) ?: return
        // A reserved batch already contains its exact wire bytes. Never re-encrypt on retry.
        if (reserved != null) {
            val decoded = codec.decode(pending.payload)
            if (decoded !== pending.payload) decoded.fill(0)
        }
        journal?.reserve(pending)
        currentCoroutineContext().ensureActive()
        val existing = provider.get(pending.key)
        if (existing == null) provider.put(pending.key, pending.payload, ifAbsent = true)
        if (provider.get(pending.key)?.contentEquals(pending.payload) != true) {
            throw SyncFailure(SyncError.INVALID_REPOSITORY)
        }
        currentCoroutineContext().ensureActive()
        adapter.markPublished(pending.changeLogIds, pending.version)
        if (journal == null) state.markPublished(pending.sequence) else journal.complete(pending)
    }

    private suspend fun prepare(minReader: Int): PendingPublication? {
        val changes = adapter.pending()
        if (changes.isEmpty()) return null
        val local = allKeys().filter { it.matchesDevice(deviceId) }
        val sequence = Math.addExact(maxOf(state.published(), local.maxOfOrNull { it.sequence } ?: 0), 1)
        val version = Instant.now().epochSecond
        val operation = UUID.randomUUID().toString()
        val v4 = minReader >= 4
        val key = SyncWireCodec.changeKey(deviceId, sequence.toULong(), version.toULong(), operation, v4)
        val objects = changes.map { JsonValue.parseObject(SyncWireCodec.encodeChange(it).decodeToString()) }
        val payload = SyncBatchCodec.encode(SyncBatch(objects, deviceId, if (v4) 4u else 3u, sequence.toULong(),
            deviceId, if (v4) operation else null))
        val ids = objects.mapNotNull { (it["change_log_id"] as? JsonValue.Number)?.asLongOrNull() }
        return PendingPublication(key, sequence, version, codec.encode(compression.encode(payload)), ids)
    }

    suspend fun resumePending() {
        if ((state as? PublicationJournal)?.pending() != null) upload(3)
    }

    suspend fun download() {
        val manifests = DeviceManifests(provider, codec).read()
        val keys = allKeys().filterNot { it.matchesDevice(deviceId) }
        val groups = keys.groupBy { key -> canonicalPath(key.pathDevice, manifests.keys) }
        for ((device, files) in groups) downloadDevice(device, files)
        verifyPublishedWatermarks(manifests)
    }

    private fun canonicalPath(path: String, devices: Set<String>): String {
        val known = devices.firstOrNull { path == it || path == SyncWireCodec.deviceHash(it) }
        return known?.let(SyncWireCodec::deviceHash) ?: path
    }

    private fun verifyPublishedWatermarks(manifests: Map<String, JsonValue.Object>) {
        for ((device, manifest) in manifests) {
            if (device == deviceId) continue
            val published = (manifest["published_max_seq"] as? JsonValue.Number)?.literal?.toULongOrNull() ?: invalid()
            if (published > adapter.cursor(SyncWireCodec.deviceHash(device)).toULong()) invalid()
        }
    }

    private suspend fun downloadDevice(device: String, files: List<TransferChangeKey>) {
        val ordered = files.sortedBy { it.sequence }
        if (ordered.map { it.sequence }.distinct().size != ordered.size) invalid()
        for (file in ordered) {
            currentCoroutineContext().ensureActive()
            val cursor = adapter.cursor(device)
            if (file.sequence <= cursor) continue
            if (file.sequence != cursor + 1) invalid()
            val bytes = provider.get(file.key) ?: invalid()
            val batch = SyncBatchCodec.decode(decodePayload(codec.decode(bytes)))
            validateBatch(file, batch)
            val changes = batch.changes.map { change -> JsonValue.Object.Builder().putAll(change)
                .put("source_device_id", batch.sourceDeviceId)
                .put("source_seq", JsonValue.Number.raw(batch.sourceSeq.toString())).build().literal().toByteArray() }
            currentCoroutineContext().ensureActive()
            adapter.apply(device, file.sequence, changes)
        }
    }

    private fun decodePayload(bytes: ByteArray): ByteArray {
        return if (bytes.take(4).toByteArray().contentEquals(byteArrayOf(0x28, 0xb5.toByte(), 0x2f, 0xfd.toByte()))) {
            compression.decode(bytes)
        } else bytes
    }

    private fun validateBatch(file: TransferChangeKey, batch: SyncBatch) {
        if (batch.sourceSeq != file.sequence.toULong() || batch.sourceDeviceId != batch.deviceId) invalid()
        if (!file.matchesDevice(batch.sourceDeviceId)) invalid()
        if (file.v4 && (batch.formatVersion != 4u || batch.operationId != file.operation)) invalid()
        if (!file.v4 && batch.formatVersion !in 2u..3u) invalid()
    }

    private suspend fun allKeys(): List<TransferChangeKey> {
        val keys = (provider.list("data_governance/changes") + provider.list("data_governance/v4/shards")).distinct()
        return keys.map { TransferChangeKey.parse(it) ?: throw SyncFailure(SyncError.INCOMPATIBLE_FORMAT) }
    }

    private fun invalid(): Nothing = throw SyncFailure(SyncError.INVALID_REPOSITORY)
}

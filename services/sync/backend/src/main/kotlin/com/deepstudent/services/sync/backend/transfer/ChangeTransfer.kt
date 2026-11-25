package com.deepstudent.services.sync.backend.transfer

import com.deepstudent.core.common.id.CorrelationId
import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.core.common.logging.LogLevel
import com.deepstudent.core.common.logging.LogRecord
import com.deepstudent.services.sync.api.DomainSyncAdapter
import com.deepstudent.services.sync.api.SyncChangeReceipt
import com.deepstudent.services.sync.api.SyncError
import com.deepstudent.services.sync.backend.ObjectProvider
import com.deepstudent.services.sync.backend.PlainSyncPayload
import com.deepstudent.services.sync.backend.SyncBatch
import com.deepstudent.services.sync.backend.SyncBatchCodec
import com.deepstudent.services.sync.backend.SyncFailure
import com.deepstudent.services.sync.backend.SyncPayloadCodec
import com.deepstudent.services.sync.backend.SyncWireCodec
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.time.Instant
import java.util.UUID

class ChangeTransfer(
    private val provider: ObjectProvider,
    private val compression: SyncCompression,
    private val state: SyncUploadState,
    private val adapter: DomainSyncAdapter,
    private val deviceId: String,
    private val codec: SyncPayloadCodec = PlainSyncPayload,
    private val publication: PublicationOptions = PublicationOptions(),
) {
    suspend fun upload(minReader: Int, correlationId: String = UUID.randomUUID().toString()) {
        val started = System.nanoTime()
        if (minReader !in 3..4) throw SyncFailure(SyncError.INCOMPATIBLE_FORMAT)
        val journal = state as? PublicationJournal
        val reserved = journal?.pending()
        val pending = reserved ?: prepare(minReader) ?: return
        validateReservation(pending, journal)
        if (reserved != null) authenticateReserved(pending)
        journal?.reserve(pending)
        report(if (reserved == null) "RESERVE" else "REPLAY", pending, correlationId, started)
        publishVerified(pending)
        report("READBACK", pending, correlationId, started)
        currentCoroutineContext().ensureActive()
        adapter.markPublishedReceipts(pending.receipts, pending.version)
        if (journal == null) state.markPublished(pending.sequence) else journal.complete(pending)
        report("ACKNOWLEDGE", pending, correlationId, started)
    }

    private fun validateReservation(pending: PendingPublication, journal: PublicationJournal?) {
        if (publication.seed != null && state.published() == 0L && !pending.seed) invalid()
        if (pending.seed && (pending.sequence != 1L || journal == null)) invalid()
    }

    private fun authenticateReserved(pending: PendingPublication) {
        // A reserved batch already contains its exact wire bytes. Never re-encrypt on retry.
        val decoded = codec.decode(pending.payload)
        if (decoded !== pending.payload) decoded.fill(0)
    }

    private suspend fun publishVerified(pending: PendingPublication) {
        currentCoroutineContext().ensureActive()
        if (provider.get(pending.key) == null) {
            try {
                provider.put(pending.key, pending.payload, ifAbsent = true)
            } catch (
                failure: SyncFailure,
            ) {
                if (failure.error != SyncError.ALREADY_EXISTS) throw failure
            }
        }
        if (provider.get(pending.key)?.contentEquals(pending.payload) != true) invalid()
    }

    private fun report(stage: String, pending: PendingPublication, correlationId: String, started: Long) {
        runCatching {
            val fields = JsonValue.Object.Builder().put("owner", "sync").put("stage", stage)
                .put("status", "ok").put("elapsedMs", (System.nanoTime() - started) / 1_000_000)
                .put("sequence", pending.sequence).put("count", pending.receipts.size)
                .put("category", if (pending.seed) "seed" else "incremental").build()
            publication.logger.log(
                LogRecord(LogLevel.INFO, "sync.publication", correlationId = CorrelationId.parse(correlationId), fields = fields),
            )
        }
    }

    private suspend fun prepare(minReader: Int): PendingPublication? {
        val inventory = if (state.published() == 0L) publication.seed?.snapshot() else null
        val changes = inventory?.changes ?: adapter.pending()
        if (changes.isEmpty() && inventory == null) return null
        val sequence = nextSequence(inventory != null)
        val version = Instant.now().epochSecond
        val operation = UUID.randomUUID().toString()
        val v4 = minReader >= 4
        val key = SyncWireCodec.changeKey(deviceId, sequence.toULong(), version.toULong(), operation, v4)
        val objects = normalizedChanges(changes)
        val payload = SyncBatchCodec.encode(
            SyncBatch(
                objects,
                deviceId,
                if (v4) 4u else 3u,
                sequence.toULong(),
                deviceId,
                if (v4) operation else null,
            ),
        )
        com.deepstudent.services.sync.backend.SyncObjectLimits.requirePayload(payload)
        val receipts = inventory?.receipts ?: objects.mapNotNull(::receipt)
        if (receipts.distinct().size != receipts.size) invalid()
        return PendingPublication(key, sequence, version, codec.encode(compression.encode(payload)), receipts, inventory != null)
    }

    private suspend fun nextSequence(seeding: Boolean): Long {
        val local = allKeys().filter { it.matchesDevice(deviceId) }
        if (seeding && local.isNotEmpty()) invalid()
        return Math.addExact(maxOf(state.published(), local.maxOfOrNull { it.sequence } ?: 0), 1)
    }

    private suspend fun normalizedChanges(changes: List<ByteArray>): List<JsonValue.Object> = changes.map {
        currentCoroutineContext().ensureActive()
        JsonValue.parseObject(SyncWireCodec.encodeChange(it).decodeToString())
    }

    private fun receipt(change: JsonValue.Object): SyncChangeReceipt? {
        val id = (change["change_log_id"] as? JsonValue.Number)?.asLongOrNull() ?: return null
        val database = (change["database_name"] as? JsonValue.Text)?.value ?: SyncChangeReceipt.VFS
        return try {
            SyncChangeReceipt(database, id)
        } catch (_: IllegalArgumentException) {
            invalid()
        }
    }

    suspend fun resumePending(correlationId: String = UUID.randomUUID().toString()) {
        if ((state as? PublicationJournal)?.pending() != null) upload(3, correlationId)
    }

    suspend fun download(correlationId: String = UUID.randomUUID().toString()) {
        val manifests = DeviceManifests(provider, codec).read()
        val keys = allKeys().filterNot { it.matchesDevice(deviceId) }
        val groups = keys.groupBy { key -> canonicalPath(key.pathDevice, manifests.keys) }
        for ((device, files) in groups) downloadDevice(device, files, correlationId)
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

    private suspend fun downloadDevice(device: String, files: List<TransferChangeKey>, correlationId: String) {
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
            val changes = batch.changes.map { change ->
                JsonValue.Object.Builder().putAll(change)
                    .put("source_device_id", batch.sourceDeviceId)
                    .put("source_seq", JsonValue.Number.raw(batch.sourceSeq.toString())).build().literal().toByteArray()
            }
            currentCoroutineContext().ensureActive()
            adapter.applyWithCorrelation(device, file.sequence, changes, correlationId)
        }
    }

    private fun decodePayload(bytes: ByteArray): ByteArray =
        if (bytes.take(4).toByteArray().contentEquals(byteArrayOf(0x28, 0xb5.toByte(), 0x2f, 0xfd.toByte()))) {
            compression.decode(bytes)
        } else {
            bytes
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

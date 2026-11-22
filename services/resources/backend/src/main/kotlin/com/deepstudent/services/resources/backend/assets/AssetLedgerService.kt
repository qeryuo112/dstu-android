package com.deepstudent.services.resources.backend.assets

import com.deepstudent.core.common.id.CorrelationId
import com.deepstudent.core.common.id.DeviceId
import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.core.common.logging.LogLevel
import com.deepstudent.core.common.logging.LogRecord
import com.deepstudent.core.common.logging.Logger
import com.deepstudent.core.common.logging.NoopLogger
import com.deepstudent.core.common.time.ClockChecks
import com.deepstudent.core.common.time.MonotonicClock
import com.deepstudent.core.common.time.SystemMonotonicClock
import com.deepstudent.core.common.time.SystemWallClock
import com.deepstudent.core.common.time.WallClock
import com.deepstudent.services.resources.api.AssetKey
import com.deepstudent.services.resources.api.assets.*
import java.util.UUID

/** The only deletion action is confined to scratch explicitly owned by S2.08. */
class AssetLedgerService(
    private val deletions: AssetDeletionReader,
    private val readers: List<AssetReferenceReader>,
    private val scratch: AssetScratchCleaner,
    private val logger: Logger = NoopLogger,
    private val clock: MonotonicClock = SystemMonotonicClock,
    private val scanner: AssetScanner? = null,
    private val wallClock: WallClock = SystemWallClock,
) : AssetLedger {
    init { require(readers.map { it.owner }.distinct().size == readers.size) { "duplicate reference owner" } }

    override fun scan(): AssetInventory = record("scan") {
        val result = checkNotNull(scanner) { "asset scanner not configured" }.scan()
        AssetInventory(AssetManifestCatalog.fromScan(result.entries, wallClock.now().toString()),
            result.skippedLinks, result.skippedSpecial)
    }

    override fun references(target: AssetTarget): AssetReferenceReport = record("references") {
        val counts = readers.map { it.inspect(target).also { count -> require(count.owner == it.owner) } }
        AssetReferenceReport(counts, AssetReferenceOwner.entries.toSet() - readers.map { it.owner }.toSet())
    }

    override fun deletionSnapshot(): AssetDeletionSnapshot = record("read_deletions") { deletions.read() }

    override fun deriveReadyTombstones(deviceId: DeviceId): AssetTombstoneManifest = record("derive_ready") {
        val entries = linkedMapOf<String, AssetTombstoneEntry>()
        for (row in deletions.read().ready) {
            AssetKey.parse(row.key) // Refuse unsupported or unsafe keys; never drop other owners silently.
            require(row.deletedAt.isNotBlank() && row.retryCount >= 0)
            require(row.size?.let { it >= 0 } != false)
            val old = entries.put(row.key, AssetTombstoneEntry(row.deletedAt, deviceId.value, row.size?.toULong()))
            require(old == null) { "duplicate outbox key" }
        }
        AssetTombstoneManifest(entries)
    }

    override fun cleanupAsset(target: AssetTarget): AssetCleanupResult = record("retain_asset") {
        AssetCleanupResult(AssetCleanupPolicy.RETAIN_SHARED_ASSET, 0)
    }

    override fun cleanupBlob(sha256: String): AssetCleanupResult = record("retain_blob") {
        require(Regex("[0-9a-f]{64}").matches(sha256)) { "invalid blob hash" }
        AssetCleanupResult(AssetCleanupPolicy.RETAIN_SHARED_BLOB, 0)
    }

    override fun cleanupOwnScratch(): AssetCleanupResult = record("cleanup_scratch") {
        val result = scratch.cleanup()
        require(result.policy == AssetCleanupPolicy.REMOVE_S208_OWN_SCRATCH)
        result
    }

    private inline fun <T> record(phase: String, block: () -> T): T {
        val correlation = CorrelationId.parse(UUID.randomUUID().toString())
        val start = clock.nowNanos()
        try {
            return block().also { emit(phase, "completed", phase.uppercase(java.util.Locale.ROOT), correlation, start) }
        } catch (failure: Exception) {
            emit(phase, "failed", failureCode(failure), correlation, start)
            throw failure
        }
    }

    private fun emit(phase: String, state: String, outcome: String, id: CorrelationId, start: Long) {
        val fields = JsonValue.Object.Builder().put("owner", "services/resources")
            .put("phase", phase).put("state", state)
            .put("elapsedMs", ClockChecks.monotonicElapsedMillis(start, clock.nowNanos()) ?: 0L)
            .put("outcome", outcome).build()
        try {
            logger.log(LogRecord(if (state == "failed") LogLevel.ERROR else LogLevel.INFO,
                "resources.asset_ledger", correlationId = id, fields = fields))
        } catch (_: Exception) { /* A broken logger cannot alter storage operations. */ }
    }
}

fun interface AssetScanner { fun scan(): AssetScanResult }
fun interface AssetDeletionReader { fun read(): AssetDeletionSnapshot }
fun interface AssetScratchCleaner { fun cleanup(): AssetCleanupResult }
interface AssetReferenceReader {
    val owner: AssetReferenceOwner
    fun inspect(target: AssetTarget): AssetReferenceCount
}

private fun failureCode(failure: Exception): String = when (failure) {
    is AssetScanException -> failure.reason.name
    is SecurityException -> "SECURITY_DENIED"
    is java.io.IOException -> "STORAGE_IO"
    is IllegalArgumentException -> "INVALID_ARGUMENT"
    else -> "OPERATION_FAILED"
}

package com.deepstudent.services.resources.api.assets

import com.deepstudent.services.resources.api.AssetDigest
import com.deepstudent.services.resources.api.AssetKey
import com.deepstudent.services.resources.api.AssetScanEntry
import com.deepstudent.core.common.id.DeviceId

/** File-level identities use the existing S0.21 AssetKey and do not identify a blob by path. */
data class AssetTarget(val key: AssetKey, val digest: AssetDigest)

enum class AssetReferenceOwner {
    VFS_FILES, VFS_RESOURCES, CHAT_ATTACHMENTS, NOTE_HISTORY, ANKI_MEDIA, REMOTE_MANIFESTS, OTHER_PATHS,
}
data class AssetReferenceCount(val owner: AssetReferenceOwner, val active: Long, val recoverable: Long) {
    init { require(active >= 0 && recoverable >= 0) }
}

/** Counts come from independent snapshots; they are never a reclamation certificate. */
data class AssetReferenceReport(val counts: List<AssetReferenceCount>, val unobservedOwners: Set<AssetReferenceOwner>) {
    val completeForReclamation: Boolean get() = false
}

enum class AssetJournalState { PREPARED, READY, PUBLISHED, CANCELLED }
data class AssetJournalEntry(val operationId: String, val key: String, val state: AssetJournalState)
data class AssetDeletionSnapshot(val ready: List<QueuedAssetTombstone>, val journal: List<AssetJournalEntry>)

/** Scanner output is a best-effort inventory, not a cross-database reclamation certificate. */
data class AssetScanResult(val entries: List<AssetScanEntry>, val skippedLinks: Int, val skippedSpecial: Int)
data class AssetInventory(val manifest: AssetFileManifest, val skippedLinks: Int, val skippedSpecial: Int) {
    /** Independent scans cannot prove a complete cross-owner or remote publication view. */
    val completeForPublication: Boolean get() = false
}

enum class AssetCleanupPolicy { RETAIN_SHARED_ASSET, RETAIN_SHARED_BLOB, REMOVE_S208_OWN_SCRATCH }
data class AssetCleanupResult(val policy: AssetCleanupPolicy, val removed: Int, val skipped: Int = 0)

/** No method publishes a tombstone or deletes a shared asset/blob. */
interface AssetLedger {
    fun scan(): AssetInventory
    fun references(target: AssetTarget): AssetReferenceReport
    fun deletionSnapshot(): AssetDeletionSnapshot
    /** Diagnostic snapshot only; publication requires later synchronized revalidation. */
    fun deriveReadyTombstones(deviceId: DeviceId): AssetTombstoneManifest
    fun cleanupAsset(target: AssetTarget): AssetCleanupResult
    fun cleanupBlob(sha256: String): AssetCleanupResult
    fun cleanupOwnScratch(): AssetCleanupResult
}

/** Fixed diagnostic codes; messages never include user names, paths or bytes. */
enum class AssetScanFailure { SECURE_HANDLES_UNAVAILABLE, STABLE_ID_UNAVAILABLE, UNSUPPORTED_NAME, LIMIT, CHANGED, COLLISION }
class AssetScanException(val reason: AssetScanFailure) : IllegalStateException("asset scan ${reason.name}")

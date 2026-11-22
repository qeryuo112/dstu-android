package com.deepstudent.services.resources.api.assets

/** Frozen desktop wire representation, separate from its diagnostic {$bigint:...} fixtures. */
data class AssetTombstoneEntry(val deletedAt: String, val deviceId: String, val size: ULong? = null)
data class AssetTombstoneManifest(val entries: Map<String, AssetTombstoneEntry>, val updatedAt: String = "")

/** Read-only view of the existing ready-only desktop outbox; not a request to publish it. */
data class QueuedAssetTombstone(val key: String, val size: Long?, val deletedAt: String, val retryCount: Long)
fun interface AssetDeletionOutbox { fun readReady(): List<QueuedAssetTombstone> }

package com.deepstudent.services.resources.backend.assets

import com.deepstudent.services.resources.api.AssetDigest
import com.deepstudent.services.resources.api.AssetKey
import com.deepstudent.services.resources.api.AssetScanEntry
import com.deepstudent.services.resources.api.assets.AssetFileManifest
import com.deepstudent.services.resources.api.assets.AssetManifestEntry

/** Builds the scoped local view only; this is not an authority for remote publication. */
object AssetManifestCatalog {
    fun fromScan(scanned: List<AssetScanEntry>, updatedAt: String): AssetFileManifest {
        val entries = linkedMapOf<String, AssetManifestEntry>()
        for (asset in scanned) {
            val old = entries.put(asset.key.value, AssetManifestEntry(asset.digest.sha256,
                asset.digest.size.toULong(), asset.updatedAt))
            require(old == null) { "duplicate asset key" }
        }
        return AssetFileManifest(entries, updatedAt)
    }

    /** Validates one S0.21 key/digest without treating other entries as owned by resources. */
    fun scopedEntry(manifest: AssetFileManifest, key: AssetKey): AssetScanEntry? {
        val entry = manifest.entries[key.value] ?: return null
        require(entry.size <= Long.MAX_VALUE.toULong()) { "asset size outside local range" }
        return AssetScanEntry(key, AssetDigest(entry.sha256, entry.size.toLong()), entry.updatedAt)
    }
}

package com.deepstudent.services.resources.api.assets

/** Optional cipher fields remain independent, as in the desktop reader. */
data class AssetCipherFields(val sha256: String? = null, val size: ULong? = null)

/** Exact file-manifest fields; unsigned integers cover the desktop u64 wire range. */
data class AssetManifestEntry(
    val sha256: String,
    val size: ULong,
    val updatedAt: String = "",
    val revision: ULong = 0u,
    val objectKey: String? = null,
    val baseSha256: String? = null,
    val deviceId: String? = null,
    val cipher: AssetCipherFields = AssetCipherFields(),
)

data class AssetFileManifest(val entries: Map<String, AssetManifestEntry>, val updatedAt: String = "")

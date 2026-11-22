package com.deepstudent.services.sync.backend.transfer

import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.services.sync.api.SyncError
import com.deepstudent.services.sync.backend.SyncFailure
import com.deepstudent.core.common.id.Digests
import com.deepstudent.services.sync.backend.SyncObjectLimits
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

internal const val BLOB_LIMIT = SyncObjectLimits.PAYLOAD_BYTES.toLong()
internal const val BLOB_MANIFEST_PREFIX = "data_governance/file_manifests/blobs/"
internal const val BLOB_LEGACY_MANIFEST = "data_governance/blobs_manifest.json"
internal const val BLOB_TOMBSTONE_PREFIX = "data_governance/tombstones/blobs/"
internal const val BLOB_LEGACY_TOMBSTONE = "data_governance/tombstones/blobs.json"
internal const val BLOB_EVENT_PREFIX = "data_governance/tombstone-events/blobs/"

internal fun blobFailure(error: SyncError = SyncError.INVALID_REPOSITORY): Nothing = throw SyncFailure(error)

internal fun blobJson(bytes: ByteArray): JsonValue.Object {
    if (bytes.size > BLOB_LIMIT) blobFailure(SyncError.LIMIT_EXCEEDED)
    return try { JsonValue.parseObject(bytes.decodeToString(throwOnInvalidSequence = true)) }
    catch (_: Exception) { blobFailure() }
}

internal fun JsonValue?.objectOrFail(): JsonValue.Object = this as? JsonValue.Object ?: blobFailure()
internal fun JsonValue?.textOrFail(): String = (this as? JsonValue.Text)?.value ?: blobFailure()
internal fun JsonValue?.optionalText(): String? = if (this == null || this == JsonValue.Null) null else textOrFail()
internal fun JsonValue?.sizeOrFail(): Long {
    val value = this as? JsonValue.Number ?: blobFailure()
    return value.asLongOrNull()?.takeIf { it >= 0 } ?: blobFailure()
}

internal fun validateBlob(blob: SyncBlob) {
    if (!Regex("[0-9a-f]{64}").matches(blob.hash)) blobFailure()
    if (!Regex("${blob.hash.take(2)}/${blob.hash}\\.[A-Za-z0-9]{1,16}").matches(blob.relativePath)) blobFailure()
    if (blob.size !in 0..BLOB_LIMIT) blobFailure(SyncError.LIMIT_EXCEEDED)
}

internal fun verifyBlob(blob: SyncBlob, bytes: ByteArray) {
    validateBlob(blob)
    if (bytes.size.toLong() != blob.size) blobFailure()
    if (Digests.sha256Hex(bytes) != blob.hash) blobFailure()
}

internal fun newerBlobTime(candidate: String, existing: String): Boolean {
    val a = parseBlobTime(candidate)
    val b = parseBlobTime(existing)
    return if (a != null && b != null) a > b else candidate > existing
}

internal fun parseBlobTime(value: String): Instant? = runCatching { OffsetDateTime.parse(value).toInstant() }.getOrNull()
    ?: runCatching { LocalDateTime.parse(value.replace(' ', 'T')).toInstant(ZoneOffset.UTC) }.getOrNull()

internal data class CipherBlob(val sha256: String, val size: Long) {
    init {
        if (!Regex("[0-9a-f]{64}").matches(sha256) || size <= 0) blobFailure()
        if (size > SyncObjectLimits.WIRE_BYTES) blobFailure(SyncError.LIMIT_EXCEEDED)
    }

    fun verify(bytes: ByteArray) {
        if (bytes.size.toLong() != size || Digests.sha256Hex(bytes) != sha256) blobFailure(SyncError.ENCRYPTED_DATA_INVALID)
    }
}

internal data class BlobManifestEntry(val blob: SyncBlob, val cipher: CipherBlob? = null)

internal object BlobManifestCodec {
    fun decode(bytes: ByteArray): Map<String, BlobManifestEntry> {
        val root = blobJson(bytes)
        val entries = root["entries"].objectOrFail()
        return entries.asMap().mapValues { (hash, raw) -> decodeEntry(hash, raw.objectOrFail()) }
    }

    private fun decodeEntry(hash: String, entry: JsonValue.Object): BlobManifestEntry {
        val cipherHash = entry["cipher_sha256"].optionalText()
        val cipherSize = entry["cipher_size"]?.takeUnless { it == JsonValue.Null }?.sizeOrFail()
        if ((cipherHash == null) != (cipherSize == null)) blobFailure()
        val blob = SyncBlob(hash, entry["relative_path"].textOrFail(), entry["size"].sizeOrFail(),
            entry["updated_at"]?.textOrFail() ?: "")
        validateBlob(blob)
        return BlobManifestEntry(blob, cipherHash?.let { CipherBlob(it, checkNotNull(cipherSize)) })
    }

    fun encode(entries: Map<String, BlobManifestEntry>, updatedAt: String): ByteArray {
        val encoded = entries.mapValues { (_, entry) ->
            val (blob, cipher) = entry
            validateBlob(blob)
            JsonValue.Object.Builder().put("relative_path", blob.relativePath).put("size", blob.size)
                .put("updated_at", blob.updatedAt).apply {
                    if (cipher != null) put("cipher_sha256", cipher.sha256).put("cipher_size", cipher.size)
                }.build()
        }
        return JsonValue.Object.Builder().put("entries", JsonValue.Object.from(encoded)).put("updated_at", updatedAt)
            .build().literal().toByteArray()
    }
}

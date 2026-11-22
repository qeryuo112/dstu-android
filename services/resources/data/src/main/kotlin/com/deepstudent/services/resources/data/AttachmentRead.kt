package com.deepstudent.services.resources.data

import com.deepstudent.core.common.id.Digests
import com.deepstudent.platform.database.SqlOperations
import com.deepstudent.services.resources.api.AttachmentVerification
import java.util.Base64

internal data class AttachmentRead(val status: AttachmentVerification, val bytes: ByteArray? = null)

internal fun readAttachment(tx: SqlOperations, file: AttachmentRow, blobs: BlobFiles): AttachmentRead {
    val resourceId = file.resourceId ?: return AttachmentRead(AttachmentVerification.MISSING_RESOURCE)
    val resource = loadResource(tx, resourceId) ?: return AttachmentRead(AttachmentVerification.MISSING_RESOURCE)
    if (resource.deleted) return AttachmentRead(AttachmentVerification.MISSING_RESOURCE)
    if (!matchesResourceHash(resource, file)) return AttachmentRead(AttachmentVerification.HASH_MISMATCH)
    val bytes = when (resource.mode) {
        "inline" -> decodeInline(resource.data)
        "external" -> readBlob(tx, resource, file, blobs)
        else -> return AttachmentRead(AttachmentVerification.MISSING_RESOURCE)
    }
    if (bytes == null) return AttachmentRead(AttachmentVerification.MISSING_BLOB)
    return verifyContent(file, bytes)
}

private fun verifyContent(file: AttachmentRow, bytes: ByteArray): AttachmentRead {
    if (bytes.size.toLong() != file.storage.size || Digests.sha256Hex(bytes) != file.storage.sha256) {
        return AttachmentRead(AttachmentVerification.HASH_MISMATCH)
    }
    return AttachmentRead(AttachmentVerification.VALID, bytes)
}

private fun loadResource(tx: SqlOperations, resourceId: String): ReadResource? =
    tx.query(
        "SELECT storage_mode,data,external_hash,deleted_at,hash FROM resources WHERE id=?", listOf(text(resourceId)),
    ) { row ->
        ReadResource(row.text(0), if (row.isNull(1)) null else row.text(1),
            if (row.isNull(2)) null else row.text(2), !row.isNull(3), row.text(4))
    }.singleOrNull()

private fun matchesResourceHash(resource: ReadResource, file: AttachmentRow): Boolean = when (resource.mode) {
    "inline" -> resource.data?.let { Digests.sha256HexUtf8(it) == resource.hash } ?: true
    "external" -> resource.hash == file.storage.sha256
    else -> true
}

private fun decodeInline(value: String?): ByteArray? {
    if (value == null) return null
    return try { Base64.getDecoder().decode(value) } catch (failure: IllegalArgumentException) { null }
}

private fun readBlob(tx: SqlOperations, resource: ReadResource, file: AttachmentRow, blobs: BlobFiles): ByteArray? {
    val hash = file.storage.blobHash ?: return null
    if (resource.externalHash != hash || hash != file.storage.sha256) return null
    val blob = tx.query("SELECT relative_path,size FROM blobs WHERE hash=?", listOf(text(hash))) {
        it.text(0) to it.long(1)
    }.singleOrNull() ?: return null
    if (blob.second != file.storage.size) return null
    return blobs.read(blob.first, hash)
}

private data class ReadResource(
    val mode: String,
    val data: String?,
    val externalHash: String?,
    val deleted: Boolean,
    val hash: String,
)

/** Streams external content without buffering it in memory; inline content is already size-limited. */
internal fun copyAttachment(
    tx: SqlOperations, file: AttachmentRow, blobs: BlobFiles, output: java.io.OutputStream,
    check: () -> Unit, progress: (Long) -> Unit,
): AttachmentVerification {
    val resource = file.resourceId?.let { loadResource(tx, it) } ?: return AttachmentVerification.MISSING_RESOURCE
    if (resource.deleted) return AttachmentVerification.MISSING_RESOURCE
    if (!matchesResourceHash(resource, file)) return AttachmentVerification.HASH_MISMATCH
    return when (resource.mode) {
        "inline" -> copyInline(file, resource, output, check, progress)
        "external" -> copyExternal(tx, file, resource, blobs, output, check, progress)
        else -> AttachmentVerification.MISSING_RESOURCE
    }
}

private fun copyInline(
    file: AttachmentRow, resource: ReadResource, output: java.io.OutputStream,
    check: () -> Unit, progress: (Long) -> Unit,
): AttachmentVerification {
    val bytes = decodeInline(resource.data) ?: return AttachmentVerification.MISSING_BLOB
    if (verifyContent(file, bytes).status != AttachmentVerification.VALID) return AttachmentVerification.HASH_MISMATCH
    var offset = 0
    check(); progress(0L)
    while (offset < bytes.size) {
        check()
        val count = minOf(64 * 1024, bytes.size - offset)
        fileOperation(FileStage.OUTPUT) { output.write(bytes, offset, count) }
        offset += count
        progress(offset.toLong())
    }
    check()
    return AttachmentVerification.VALID
}

private fun copyExternal(
    tx: SqlOperations, file: AttachmentRow, resource: ReadResource, blobs: BlobFiles, output: java.io.OutputStream,
    check: () -> Unit, progress: (Long) -> Unit,
): AttachmentVerification {
    val hash = file.storage.blobHash ?: return AttachmentVerification.MISSING_BLOB
    if (resource.externalHash != hash || hash != file.storage.sha256) return AttachmentVerification.MISSING_BLOB
    val entry = tx.query("SELECT relative_path,size FROM blobs WHERE hash=?", listOf(text(hash))) {
        it.text(0) to it.long(1)
    }.singleOrNull() ?: return AttachmentVerification.MISSING_BLOB
    if (entry.second != file.storage.size) return AttachmentVerification.HASH_MISMATCH
    return if (blobs.copy(entry.first, hash, entry.second, output, check, progress, verify = true)) {
        AttachmentVerification.VALID
    } else AttachmentVerification.MISSING_BLOB
}

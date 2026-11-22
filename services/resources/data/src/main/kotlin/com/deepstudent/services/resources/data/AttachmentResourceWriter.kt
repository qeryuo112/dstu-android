package com.deepstudent.services.resources.data

import com.deepstudent.core.common.id.Digests
import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.platform.database.SqlOperations
import com.deepstudent.platform.database.SqlValue
import java.util.Base64

internal data class PreparedAttachment(
    val identity: AttachmentIdentity,
    val name: String,
    val mediaType: String,
    val hash: String,
    val size: Long,
    val inlineBytes: ByteArray?,
    val blobPath: String?,
    val nowMillis: Long,
)

internal data class AttachmentIdentity(val id: String, val resourceId: String)

internal fun SqlOperations.registerResource(input: PreparedAttachment): String {
    val inline = input.blobPath == null
    val data = if (inline) Base64.getEncoder().encodeToString(input.inlineBytes ?: corruptAttachment()) else null
    val resourceHash = data?.let(Digests::sha256HexUtf8) ?: input.hash
    val existing = resourceByHash(resourceHash)
    if (!inline) registerBlob(input)
    if (existing != null) {
        validateExisting(existing, input, data)
        return existing.id
    }
    val metadata = JsonValue.Object.Builder()
        .put("name", input.name).put("mimeType", input.mediaType).put("size", input.size).build().literal()
    execute(
        """INSERT INTO resources
            (id,hash,type,source_id,source_table,storage_mode,data,external_hash,metadata_json,ref_count,created_at,updated_at)
            VALUES (?,?,?,?,?,?,?,?,?,0,?,?)""".trimIndent(),
        listOf(
            text(input.identity.resourceId), text(resourceHash), text(if (input.mediaType.startsWith("image/")) "image" else "file"),
            text(input.identity.id), text("files"), text(if (inline) "inline" else "external"), nullable(data),
            nullable(if (inline) null else input.hash), text(metadata),
            SqlValue.Integer(input.nowMillis), SqlValue.Integer(input.nowMillis),
        ),
    )
    return input.identity.resourceId
}

private fun validateExisting(existing: StoredResource, input: PreparedAttachment, data: String?) {
    if (existing.deleted || existing.type !in setOf("file", "image")) resourceConflict()
    if (data != null) {
        if (existing.mode != "inline" || existing.data != data) resourceConflict()
    } else if (existing.mode != "external" || existing.externalHash != input.hash) {
        resourceConflict()
    }
}

private fun SqlOperations.registerBlob(input: PreparedAttachment) {
    val pending = query(
        "SELECT 1 FROM __file_deletion_journal WHERE target_kind='blob' AND entity_key=? AND state='prepared' LIMIT 1",
        listOf(text(input.hash)),
    ) { it.long(0) }.isNotEmpty()
    if (pending) resourceConflict()
    val current = query("SELECT relative_path,size FROM blobs WHERE hash=?", listOf(text(input.hash))) {
        it.text(0) to it.long(1)
    }.singleOrNull()
    if (current != null && (current.first != input.blobPath || current.second != input.size)) {
        resourceConflict()
    }
    execute(
        """INSERT INTO blobs (hash,relative_path,size,mime_type,ref_count,created_at) VALUES (?,?,?,?,1,?)
            ON CONFLICT(hash) DO UPDATE SET ref_count=ref_count+1""".trimIndent(),
        listOf(
            text(input.hash), text(input.blobPath ?: storageFailure()), SqlValue.Integer(input.size),
            text(input.mediaType), SqlValue.Integer(input.nowMillis),
        ),
    )
    execute("DELETE FROM __blob_deletion_queue WHERE hash=?", listOf(text(input.hash)))
    execute(
        """UPDATE __file_deletion_journal SET state='cancelled',cancelled_at=?,last_error='blob recreated'
            WHERE target_kind='blob' AND entity_key=? AND state='ready'""".trimIndent(),
        listOf(text(java.time.Instant.ofEpochMilli(input.nowMillis).toString()), text(input.hash)),
    )
}

package com.deepstudent.services.resources.data

import com.deepstudent.core.common.id.ResourceId
import com.deepstudent.platform.database.SqlOperations
import com.deepstudent.platform.database.SqlRow
import com.deepstudent.services.resources.api.AttachmentMetadata
import com.deepstudent.services.resources.api.AttachmentStorage
import com.deepstudent.services.resources.api.AttachmentTimes

internal data class AttachmentRow(
    val id: String,
    val resourceId: String?,
    val name: String,
    val mediaType: String,
    val storage: AttachmentStorage,
    val times: AttachmentTimes,
) {
    fun metadata(): AttachmentMetadata = AttachmentMetadata(
        id,
        ResourceId.parse(resourceId ?: corruptAttachment()),
        name,
        mediaType,
        storage,
        times,
    )
}

internal const val FILE_COLUMNS =
    "f.id,f.resource_id,COALESCE(f.name,f.file_name),COALESCE(f.mime_type,'application/octet-stream')," +
        "f.size,f.sha256,f.blob_hash,f.created_at,f.updated_at,f.deleted_at"

private const val COLUMNS =
    "id,resource_id,COALESCE(name,file_name),COALESCE(mime_type,'application/octet-stream')," +
        "size,sha256,blob_hash,created_at,updated_at,deleted_at"

internal fun SqlOperations.attachmentById(id: String): AttachmentRow? =
    query("SELECT $COLUMNS FROM files WHERE id=?", listOf(text(id)), ::mapAttachment).singleOrNull()

internal fun SqlOperations.attachmentByHash(hash: String): AttachmentRow? =
    query("SELECT $COLUMNS FROM files WHERE sha256=?", listOf(text(hash)), ::mapAttachment).singleOrNull()

internal fun SqlOperations.attachmentByResource(id: String): AttachmentRow? = query(
    "SELECT $COLUMNS FROM files WHERE resource_id=? ORDER BY deleted_at IS NULL DESC,id LIMIT 1",
    listOf(text(id)),
    ::mapAttachment,
).singleOrNull()

internal fun mapAttachment(row: SqlRow): AttachmentRow = AttachmentRow(
    id = row.text(0),
    resourceId = if (row.isNull(1)) null else row.text(1),
    name = row.text(2),
    mediaType = row.text(3),
    storage = AttachmentStorage(row.long(4), row.text(5), if (row.isNull(6)) null else row.text(6)),
    times = AttachmentTimes(row.text(7), row.text(8), if (row.isNull(9)) null else row.text(9)),
)

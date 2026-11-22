package com.deepstudent.services.resources.data

import com.deepstudent.core.common.error.DstuError
import com.deepstudent.core.common.error.DstuException
import com.deepstudent.platform.database.SqlOperations
import com.deepstudent.platform.database.SqlValue

internal data class StoredResource(
    val id: String,
    val type: String,
    val mode: String,
    val data: String?,
    val externalHash: String?,
    val sourceId: String?,
    val deleted: Boolean,
)

internal fun SqlOperations.resourceByHash(hash: String): StoredResource? = query(
    "SELECT id,type,storage_mode,data,external_hash,source_id,deleted_at FROM resources WHERE hash=?",
    listOf(text(hash)),
) { row ->
    StoredResource(
        row.text(0), row.text(1), row.text(2), if (row.isNull(3)) null else row.text(3),
        if (row.isNull(4)) null else row.text(4), if (row.isNull(5)) null else row.text(5), !row.isNull(6),
    )
}.singleOrNull()

internal fun resourceConflict(): Nothing =
    throw DstuException(DstuError.conflict("RESOURCE_CONFLICT", "resource identity or content conflicts"))

internal fun attachmentMissing(): Nothing =
    throw DstuException(DstuError.notFound("ATTACHMENT_NOT_FOUND", "attachment not found"))

internal fun corruptAttachment(): Nothing =
    throw DstuException(DstuError.internal("ATTACHMENT_CORRUPT", "attachment verification failed"))

internal fun storageFailure(): Nothing =
    throw DstuException(DstuError.internal("RESOURCE_STORAGE_FAILED", "resource storage failed"))

internal fun text(value: String): SqlValue = SqlValue.Text(value)
internal fun nullable(value: String?): SqlValue = value?.let(SqlValue::Text) ?: SqlValue.Null

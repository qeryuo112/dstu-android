package com.deepstudent.services.resources.data.assets

import com.deepstudent.platform.database.SqlDatabase
import com.deepstudent.platform.database.SqlValue
import com.deepstudent.services.resources.api.assets.AssetReferenceCount
import com.deepstudent.services.resources.api.assets.AssetReferenceOwner
import com.deepstudent.services.resources.api.assets.AssetTarget
import com.deepstudent.services.resources.backend.assets.AssetReferenceReader

/** Counts include soft-deleted rows; they do not certify all local or remote references. */
class SqlVfsFilesReferences(private val database: SqlDatabase) : AssetReferenceReader {
    override val owner = AssetReferenceOwner.VFS_FILES
    override fun inspect(target: AssetTarget): AssetReferenceCount = database.readSnapshot {
        val hash = SqlValue.Text(target.digest.sha256)
        query("""SELECT COALESCE(SUM(CASE WHEN deleted_at IS NULL THEN 1 ELSE 0 END),0),
            COALESCE(SUM(CASE WHEN deleted_at IS NOT NULL THEN 1 ELSE 0 END),0)
            FROM files WHERE ${vfsFileBlobColumns.joinToString(" OR ") { "$it=?" }}""".trimIndent(),
            vfsFileBlobColumns.map { hash }) { AssetReferenceCount(owner, it.long(0), it.long(1)) }.single()
    }
}

class SqlVfsResourceReferences(private val database: SqlDatabase) : AssetReferenceReader {
    override val owner = AssetReferenceOwner.VFS_RESOURCES
    override fun inspect(target: AssetTarget): AssetReferenceCount = database.readSnapshot {
        query("""SELECT COALESCE(SUM(CASE WHEN deleted_at IS NULL THEN 1 ELSE 0 END),0),
            COALESCE(SUM(CASE WHEN deleted_at IS NOT NULL THEN 1 ELSE 0 END),0)
            FROM resources WHERE $VFS_RESOURCE_BLOB_COLUMN=?""".trimIndent(), listOf(SqlValue.Text(target.digest.sha256))) {
            AssetReferenceCount(owner, it.long(0), it.long(1))
        }.single()
    }
}

class SqlChatAttachmentReferences(private val database: SqlDatabase) : AssetReferenceReader {
    override val owner = AssetReferenceOwner.CHAT_ATTACHMENTS
    override fun inspect(target: AssetTarget): AssetReferenceCount = database.readSnapshot {
        query("""SELECT COALESCE(SUM(CASE WHEN deleted_at IS NULL THEN 1 ELSE 0 END),0),
            COALESCE(SUM(CASE WHEN deleted_at IS NOT NULL THEN 1 ELSE 0 END),0)
            FROM chat_v2_attachments WHERE storage_path=? OR content_hash=?""".trimIndent(),
            listOf(SqlValue.Text(target.key.value), SqlValue.Text(target.digest.sha256))) {
            AssetReferenceCount(owner, it.long(0), it.long(1))
        }.single()
    }
}

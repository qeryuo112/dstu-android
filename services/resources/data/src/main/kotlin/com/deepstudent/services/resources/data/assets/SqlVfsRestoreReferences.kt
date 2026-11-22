package com.deepstudent.services.resources.data.assets

import com.deepstudent.platform.database.SqlReadOperations

class VfsRestoreBlob internal constructor(val hash: String, val relative: String, val length: Long, val required: Boolean)

enum class VfsRestoreReferenceError { FILE_BLOB_MISMATCH, MISSING_BLOB_METADATA, INVALID_BLOB_METADATA, TOO_MANY_BLOBS }

class VfsRestoreReferenceException(val code: VfsRestoreReferenceError) : IllegalStateException(code.name)

fun readVfsRestoreBlobs(database: SqlReadOperations): List<VfsRestoreBlob> {
    database.query<Unit>("""
        SELECT 1 FROM files f LEFT JOIN blobs b ON b.hash=f.blob_hash
        WHERE f.blob_hash IS NOT NULL AND (b.hash IS NULL OR lower(f.sha256)<>lower(b.hash) OR f.size<>b.size)
        LIMIT 1
    """.trimIndent()) { fail(VfsRestoreReferenceError.FILE_BLOB_MISMATCH) }
    val entries = database.query(restoreBlobsSql) { row ->
        if (row.isNull(1)) fail(VfsRestoreReferenceError.MISSING_BLOB_METADATA)
        if (row.long(4) != 1L) fail(VfsRestoreReferenceError.INVALID_BLOB_METADATA)
        VfsRestoreBlob(row.text(0), row.text(1), row.long(2), row.long(3) == 1L)
    }
    if (entries.size > 100_000) fail(VfsRestoreReferenceError.TOO_MANY_BLOBS)
    return entries
}

internal val vfsFileBlobColumns = listOf("blob_hash", "compressed_blob_hash", "content_hash", "sha256")
internal const val VFS_RESOURCE_BLOB_COLUMN = "external_hash"

private fun fail(code: VfsRestoreReferenceError): Nothing = throw VfsRestoreReferenceException(code)

private val referenceCte = "WITH references_to_blobs(hash) AS MATERIALIZED (${blobReferences().joinToString(" UNION ")})"

fun presentVfsBlobReferences(database: SqlReadOperations): Set<String> = database.query("""
    $referenceCte SELECT b.hash FROM blobs b JOIN references_to_blobs r ON r.hash=b.hash LIMIT 100001
""".trimIndent()) { it.text(0) }.also {
    if (it.size > 100_000) fail(VfsRestoreReferenceError.TOO_MANY_BLOBS)
}.toSet()

private val restoreBlobsSql = """
    $referenceCte
    SELECT b.hash,b.relative_path,b.size,(b.ref_count>0 OR r.hash IS NOT NULL),
           typeof(b.size)='integer' AND b.size>=0 AND typeof(b.ref_count)='integer' AND b.ref_count>=0
    FROM blobs b LEFT JOIN references_to_blobs r ON r.hash=b.hash
    UNION ALL
    SELECT r.hash,NULL,NULL,1,0 FROM references_to_blobs r
    WHERE r.hash IS NOT NULL AND r.hash<>'' AND NOT EXISTS (SELECT 1 FROM blobs b WHERE b.hash=r.hash)
    LIMIT 100001
""".trimIndent()

private fun blobReferences() = vfsFileBlobColumns.map { column ->
    if (column in listOf("sha256", "content_hash")) {
        "SELECT b.hash FROM files f JOIN blobs b ON b.hash=f.$column"
    } else {
        "SELECT $column FROM files WHERE $column IS NOT NULL"
    }
} + listOf(
    "SELECT $VFS_RESOURCE_BLOB_COLUMN FROM resources WHERE $VFS_RESOURCE_BLOB_COLUMN IS NOT NULL",
    "SELECT image_blob_hash FROM vfs_index_units WHERE image_blob_hash IS NOT NULL",
    previewReferences("files"), previewReferences("exam_sheets"),
    jsonReferences("questions", "images_json", "'hash'"),
    jsonReferences("qbank_materials", "images_json", "'hash'"),
    jsonReferences("qbank_sources", "descriptor_json", "'blob_hash'"),
    jsonReferences("exam_sheets", "import_state_json", "'hash','blob_hash'"),
    jsonReferences("qbank_import_jobs", "document_json", "'hash'", activeImports),
    jsonReferences("qbank_import_jobs", "metadata_json", "'blob_hash','content_hash'", activeImports),
) + listOf("qbank_papers", "qbank_practice_drafts", "qbank_practice_attempts").flatMap { table ->
    listOf(jsonReferences(table, "document_json", "'hash'"), answerReferences(table))
}

private const val activeImports = " AND d.status IN ('ready','needs_review','partial')"

private fun jsonReferences(table: String, field: String, keys: String, filter: String = "") = """
    SELECT r.atom FROM $table d, json_tree(CASE WHEN json_valid(d.$field) THEN d.$field END) r
    WHERE r.type='text' AND r.key IN ($keys) AND r.atom<>''$filter
""".trimIndent()

private fun previewReferences(table: String) = """
    SELECT r.atom FROM $table d, json_each(CASE WHEN json_valid(d.preview_json) THEN d.preview_json END, '$.pages') p,
        json_each(CASE WHEN p.type='object' THEN p.value END) r
    WHERE r.type='text' AND r.key IN ('blob_hash','blobHash','compressed_blob_hash','compressedBlobHash') AND r.atom<>''
""".trimIndent()

private fun answerReferences(table: String) = """
    SELECT r.atom FROM $table d, json_each(CASE WHEN json_valid(d.document_json) THEN d.document_json END, '$.answers') a,
        json_tree(CASE WHEN json_valid(a.value) THEN a.value END) r
    WHERE r.key='hash' AND r.type='text' AND r.atom<>''
""".trimIndent()

package com.deepstudent.services.resources.data

import com.deepstudent.platform.database.SqlOperations
import com.deepstudent.platform.database.SqlValue
import com.deepstudent.services.resources.api.AttachmentMetadata
import com.deepstudent.services.resources.api.WorkspaceFileArea
import com.deepstudent.services.resources.api.WorkspaceFileIds
import com.deepstudent.services.resources.api.WorkspaceFileWrite
import com.deepstudent.services.resources.api.workspaceVersion
import java.time.Instant

/** Indexed by files.id; the join excludes governance, notes, configuration and orphaned rows. */
private const val WORKSPACE_FILTER = "f.id GLOB 'att_ws_*' AND length(f.id) = 39 AND substr(f.id, 8) NOT GLOB '*[^0-9a-f]*'"
private const val RESOURCE_FILTER = "f.deleted_at IS NULL AND r.deleted_at IS NULL AND " +
    "r.source_table = 'files' AND r.type IN ('file','image') AND r.storage_mode IN ('inline','external')"

internal fun SqlOperations.visibleFiles(area: WorkspaceFileArea, after: String?, limit: Int): List<AttachmentMetadata> {
    require(limit in 1..1000)
    val filter = if (area == WorkspaceFileArea.WORKSPACE) WORKSPACE_FILTER else "NOT ($WORKSPACE_FILTER)"
    return query(
        "SELECT $FILE_COLUMNS FROM files f JOIN resources r ON r.id = f.resource_id " +
            "WHERE $RESOURCE_FILTER AND ($filter) AND f.id > ? ORDER BY f.id LIMIT ?",
        listOf(text(after.orEmpty()), SqlValue.Integer(limit.toLong())),
        ::mapAttachment,
    ).filter { WorkspaceFileIds.isReadable(it.id) }.map { it.metadata() }
}

internal fun SqlOperations.visibleFile(id: String): AttachmentRow? {
    if (!WorkspaceFileIds.isReadable(id)) return null
    return query(
        "SELECT $FILE_COLUMNS FROM files f JOIN resources r ON r.id = f.resource_id WHERE $RESOURCE_FILTER AND f.id = ?",
        listOf(text(id)),
        ::mapAttachment,
    ).singleOrNull()
}

internal fun SqlOperations.workspaceEditRow(edit: WorkspaceFileWrite): AttachmentRow? {
    val row = attachmentById(edit.id)
    if (edit.expected == null) {
        if (row != null) resourceConflict()
        return null
    }
    val active = visibleFile(edit.id) ?: attachmentMissing()
    if (active.metadata().workspaceVersion() != edit.expected) resourceConflict()
    return active
}

/** Repoints the same file identity. Previously published resources remain valid for prior references. */
internal fun SqlOperations.repointWorkspaceFile(input: PreparedAttachment, resourceId: String, now: Instant) {
    execute(
        "UPDATE files SET resource_id=?,blob_hash=?,sha256=?,content_hash=?,size=?,updated_at=? WHERE id=?",
        listOf(
            text(resourceId),
            nullable(input.blobPath?.let { input.hash }),
            text(input.hash),
            text(input.hash),
            SqlValue.Integer(input.size),
            text(timestamp(now)),
            text(input.identity.id),
        ),
    )
}

internal fun validateWorkspaceTitle(title: String) {
    require(
        title.isNotBlank() && title.length <= 240 && !title.startsWith('.') &&
            title.none { it.isISOControl() || it == '/' || it == '\\' || Character.getType(it) == Character.FORMAT.toInt() },
    ) {
        "invalid display name"
    }
}

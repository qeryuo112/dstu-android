package com.deepstudent.app.storage.documents

import android.provider.DocumentsContract.Document
import com.deepstudent.services.resources.api.AttachmentMetadata
import com.deepstudent.services.resources.api.WorkspaceFileArea
import com.deepstudent.services.resources.api.WorkspaceFileIds
import com.deepstudent.services.resources.api.WorkspaceFileVersion
import com.deepstudent.services.resources.api.workspaceVersion
import com.deepstudent.services.resources.backend.AttachmentService
import java.io.FileNotFoundException
import java.time.Instant

internal enum class DocumentRoot(val id: String, val label: String, val writable: Boolean, val area: WorkspaceFileArea) {
    WORKSPACE("workspace", "Deep Student 工作区", true, WorkspaceFileArea.WORKSPACE),
    ATTACHMENTS("attachments", "Deep Student 附件（只读）", false, WorkspaceFileArea.ATTACHMENTS),
    ;

    fun documentId(id: String): String = "${this.id}:$id"

    companion object {
        fun find(id: String) = entries.firstOrNull { it.id == id }
    }
}

internal data class WorkspaceDocumentKey(val root: DocumentRoot, val fileId: String) {
    val documentId: String get() = root.documentId(fileId)

    companion object {
        fun parse(documentId: String): WorkspaceDocumentKey? {
            val root = DocumentRoot.find(documentId.substringBefore(':')) ?: return null
            val id = documentId.substringAfter(':', "")
            if (!WorkspaceFileIds.isReadable(id) || WorkspaceFileIds.isOwned(id) != root.writable) return null
            return WorkspaceDocumentKey(root, id)
        }
    }
}

internal data class WorkspaceDocumentInfo(
    val key: WorkspaceDocumentKey,
    val title: String,
    val mime: String,
    val size: Long,
    val version: WorkspaceFileVersion? = null,
) {
    val maxSize: Int
        get() = if (mime.startsWith("image/", true)) AttachmentService.IMAGE_LIMIT else AttachmentService.FILE_LIMIT

    val modified: Long? get() = version?.updatedAt?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

    companion object {
        fun from(row: AttachmentMetadata): WorkspaceDocumentInfo {
            val root = if (WorkspaceFileIds.isOwned(row.id)) DocumentRoot.WORKSPACE else DocumentRoot.ATTACHMENTS
            return WorkspaceDocumentInfo(WorkspaceDocumentKey(root, row.id), row.name, row.mediaType, row.size, row.workspaceVersion())
        }
    }
}

internal fun requireDocumentTitle(raw: String): String {
    if (raw.isBlank() || raw.length > 240 || raw.startsWith('.') || raw.any(::invalidTitleCharacter)) {
        throw FileNotFoundException("invalid display name")
    }
    return raw
}

private fun invalidTitleCharacter(ch: Char): Boolean = ch.isISOControl() || ch in "/\\" || Character.getType(ch) == Character.FORMAT.toInt()

internal fun requireDocumentMime(mime: String): String {
    if (mime == Document.MIME_TYPE_DIR || mime.length > 255 || !Regex("[A-Za-z0-9._+-]+/[A-Za-z0-9._+-]+").matches(mime)) {
        throw FileNotFoundException("unsupported document type")
    }
    return mime
}

internal fun missingDocument(): Nothing = throw FileNotFoundException("document is outside the workspace or no longer exists")

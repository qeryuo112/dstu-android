package com.deepstudent.services.resources.api

import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

enum class WorkspaceFileArea { WORKSPACE, ATTACHMENTS }

/** Stable IDs use a reserved VFS namespace, never a pathname or a caller-chosen output directory. */
object WorkspaceFileIds {
    private val owned = Regex("att_ws_[0-9a-f]{32}")
    private val readable = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
    fun create(): String = "att_ws_${UUID.randomUUID().toString().replace("-", "")}"
    fun isOwned(id: String): Boolean = owned.matches(id)
    fun isReadable(id: String): Boolean = readable.matches(id)
}

data class WorkspaceFileVersion(val sha256: String, val updatedAt: String, val displayName: String)

fun AttachmentMetadata.workspaceVersion() = WorkspaceFileVersion(sha256, updatedAt, name)

data class WorkspaceFileWrite(
    val id: String,
    val source: StreamAttachmentRequest,
    /** Null is create-only; both the ID and version are checked in the publishing transaction. */
    val expected: WorkspaceFileVersion?,
)

/** Provider sees attachment records and streams only, not raw files, SQL or active-slot paths. */
interface WorkspaceFiles {
    fun page(area: WorkspaceFileArea, afterId: String?, limit: Int): List<AttachmentMetadata>
    fun stat(id: String): AttachmentMetadata?
    suspend fun read(id: String, output: OutputStream, check: () -> Unit = {}): AttachmentMetadata?
    suspend fun write(request: WorkspaceFileWrite, input: InputStream): AttachmentMetadata
    suspend fun renameWorkspace(id: String, title: String): AttachmentMetadata
    suspend fun deleteWorkspace(id: String)
}

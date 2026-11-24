package com.deepstudent.app.storage.documents

import android.content.Context
import com.deepstudent.services.resources.api.AttachmentMetadata
import com.deepstudent.services.resources.api.WorkspaceFileArea
import com.deepstudent.services.resources.api.WorkspaceFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Narrow owner API for the workbench. No provider permission can call it over Binder. */
internal class WorkspaceDocumentPrivacy(private val context: Context, private val files: WorkspaceFiles) {
    private val authority = "${context.packageName}.documents.workspace"

    suspend fun list(): List<WorkspaceSharingRow> = withContext(Dispatchers.IO) {
        val policy = WorkspaceSharePolicy.open(context)
        buildList {
            for (area in WorkspaceFileArea.entries) {
                var after: String? = null
                while (true) {
                    val page = files.page(area, after, PAGE_SIZE)
                    page.forEach { row -> add(WorkspaceSharingRow(row, policy.hidden(WorkspaceDocumentInfo.from(row).key))) }
                    after = page.lastOrNull()?.id ?: break
                }
            }
        }
    }

    suspend fun makePrivate(row: AttachmentMetadata) = withContext(Dispatchers.IO) {
        val id = WorkspaceDocumentInfo.from(row).key.documentId
        requireNotNull(context.contentResolver.acquireContentProviderClient(authority)).use { client ->
            val provider = client.localContentProvider as? WorkspaceDocumentsProvider ?: error("local document provider unavailable")
            provider.revokeExternalAccess(id)
        }
    }
    private companion object {
        const val PAGE_SIZE = 100
    }
}

internal data class WorkspaceSharingRow(val file: AttachmentMetadata, val isPrivate: Boolean)

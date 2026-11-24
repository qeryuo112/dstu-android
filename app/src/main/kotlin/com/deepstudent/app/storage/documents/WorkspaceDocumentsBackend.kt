package com.deepstudent.app.storage.documents

import android.content.Context
import com.deepstudent.app.DstuApplication
import com.deepstudent.app.storage.AndroidOperationLogger
import com.deepstudent.core.common.id.Digests
import com.deepstudent.services.resources.api.StreamAttachmentRequest
import com.deepstudent.services.resources.api.WorkspaceFileWrite
import com.deepstudent.services.resources.api.WorkspaceFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.IOException
import java.io.OutputStream

internal class WorkspaceDocumentsBackend private constructor(
    private val resources: WorkspaceFiles,
    private val reservations: WorkspaceDocumentReservations,
) {
    private val edits = Any()

    fun query(key: WorkspaceDocumentKey): WorkspaceDocumentInfo? =
        resources.stat(key.fileId)?.let(WorkspaceDocumentInfo::from) ?: reservations.get(key.fileId)

    fun visit(root: DocumentRoot, consume: (WorkspaceDocumentInfo) -> Unit) {
        if (root.writable) reservations.list().filter { resources.stat(it.key.fileId) == null }.forEach(consume)
        var last: String? = null
        while (true) {
            val page = resources.page(root.area, last, 200)
            page.forEach { consume(WorkspaceDocumentInfo.from(it)) }
            last = page.lastOrNull()?.id ?: return
        }
    }

    fun reserve(title: String, mime: String): WorkspaceDocumentInfo = synchronized(edits) { reservations.reserve(title, mime) }

    fun read(info: WorkspaceDocumentInfo, out: OutputStream, check: () -> Unit) {
        if (info.version == null) return
        val current = runBlocking(Dispatchers.IO) { resources.read(info.key.fileId, out, check) }?.let(WorkspaceDocumentInfo::from)
        if (current != info) throw IOException("document changed while opening")
    }

    fun write(info: WorkspaceDocumentInfo, scratch: File): WorkspaceDocumentInfo = synchronized(edits) {
        if (!info.key.root.writable || query(info.key) != info) throw IOException("document changed while editing")
        val request = WorkspaceFileWrite(info.key.fileId, StreamAttachmentRequest(info.title, info.mime, scratch.length()), info.version)
        val result = runBlocking(Dispatchers.IO) { scratch.inputStream().use { resources.write(request, it) } }
        reservations.discard(info.key.fileId)
        AndroidOperationLogger.info("workspace.documents.write_committed")
        WorkspaceDocumentInfo.from(result)
    }

    fun rename(key: WorkspaceDocumentKey, title: String) = synchronized(edits) {
        if (!key.root.writable) missingDocument()
        val info = query(key) ?: missingDocument()
        val name = requireDocumentTitle(title)
        if (info.title == name) return@synchronized
        if (info.version == null) {
            reservations.rename(key.fileId, name)
        } else {
            runBlocking(Dispatchers.IO) { resources.renameWorkspace(key.fileId, name) }
        }
        AndroidOperationLogger.info("workspace.documents.renamed")
    }

    fun delete(key: WorkspaceDocumentKey) = synchronized(edits) {
        if (!key.root.writable) missingDocument()
        val info = query(key) ?: missingDocument()
        if (info.version != null) runBlocking(Dispatchers.IO) { resources.deleteWorkspace(key.fileId) }
        reservations.discard(key.fileId)
        AndroidOperationLogger.info("workspace.documents.deleted")
    }

    companion object {
        fun open(context: Context): WorkspaceDocumentsBackend {
            val app = context.applicationContext as DstuApplication
            val storage = runBlocking(Dispatchers.IO) { app.storage() }
            val slot = Digests.sha256HexUtf8(storage.activeRoot.canonicalPath)
            val directory = File(context.noBackupFilesDir, "workspace-requests/$slot")
            return WorkspaceDocumentsBackend(storage.workspaceFiles, WorkspaceDocumentReservations(directory))
        }
    }
}

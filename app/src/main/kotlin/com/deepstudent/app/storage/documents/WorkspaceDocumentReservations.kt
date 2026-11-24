package com.deepstudent.app.storage.documents

import android.util.AtomicFile
import com.deepstudent.services.resources.api.WorkspaceFileIds
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/** A DocumentsUI create reserves an ID; VFS publishes exactly once on the first successful write. */
internal class WorkspaceDocumentReservations(private val directory: File) {
    @Synchronized
    fun list(): List<WorkspaceDocumentInfo> = files().mapNotNull(::read)

    @Synchronized
    fun get(id: String): WorkspaceDocumentInfo? = read(id)

    @Synchronized
    fun reserve(title: String, mime: String): WorkspaceDocumentInfo {
        if (files().size >= MAX_PENDING) throw IOException("too many unfinished workspace documents")
        val key = WorkspaceDocumentKey(DocumentRoot.WORKSPACE, WorkspaceFileIds.create())
        val reservation = WorkspaceDocumentInfo(key, requireDocumentTitle(title), requireDocumentMime(mime), 0)
        persist(reservation)
        return reservation
    }

    @Synchronized
    fun rename(id: String, title: String) {
        persist((read(id) ?: missingDocument()).copy(title = requireDocumentTitle(title)))
    }

    @Synchronized
    fun discard(id: String) {
        if (WorkspaceFileIds.isOwned(id)) handle(id).delete()
    }

    private fun files(): List<String> = directory.list().orEmpty().filter(WorkspaceFileIds::isOwned)

    private fun read(id: String): WorkspaceDocumentInfo? {
        if (!WorkspaceFileIds.isOwned(id)) return null
        val lines = try {
            handle(id).openRead().use { input -> input.bufferedReader().readLines() }
        } catch (_: FileNotFoundException) {
            return null
        }
        if (lines.size != 2) return null
        return WorkspaceDocumentInfo(
            WorkspaceDocumentKey(DocumentRoot.WORKSPACE, id),
            requireDocumentTitle(lines[0]),
            requireDocumentMime(lines[1]),
            0,
        )
    }

    private fun persist(value: WorkspaceDocumentInfo) {
        check(directory.isDirectory || directory.mkdirs()) { "reservation directory unavailable" }
        val file = handle(value.key.fileId)
        val output = file.startWrite()
        try {
            output.write("${value.title}\n${value.mime}".toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
        } catch (failure: Throwable) {
            file.failWrite(output)
            throw failure
        }
    }

    private fun handle(id: String): AtomicFile = AtomicFile(File(directory, id))

    private companion object {
        const val MAX_PENDING = 128
    }
}

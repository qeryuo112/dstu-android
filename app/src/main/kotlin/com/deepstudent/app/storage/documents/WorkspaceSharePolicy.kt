package com.deepstudent.app.storage.documents

import android.content.Context
import android.os.Process
import java.io.File
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption

/** Local provider policy, outside the shared VFS database and the backup/sync payload. */
internal class WorkspaceSharePolicy(private val directory: File) {
    private val lock = Any()
    fun deny(key: WorkspaceDocumentKey) = synchronized(lock) {
        if (!directory.isDirectory && !directory.mkdirs()) error("workspace privacy directory unavailable")
        val marker = marker(key)
        try {
            Files.createFile(marker)
        } catch (_: FileAlreadyExistsException) {
            // An earlier revocation stays in force.
        }
    }

    fun hidden(key: WorkspaceDocumentKey): Boolean = synchronized(lock) {
        !Files.notExists(marker(key), LinkOption.NOFOLLOW_LINKS)
    }

    fun check(key: WorkspaceDocumentKey, uid: Int) = requireAccess(key, uid == Process.myUid())

    fun requireAccess(key: WorkspaceDocumentKey, owner: Boolean) {
        if (!owner && hidden(key)) throw SecurityException("workspace document sharing was withdrawn")
    }

    /** Serialize the final content mutation with owner revocation. */
    fun <T> commit(key: WorkspaceDocumentKey, uid: Int, action: () -> T): T = synchronized(lock) {
        check(key, uid)
        action()
    }

    private fun marker(key: WorkspaceDocumentKey) = File(directory, key.fileId).toPath()

    companion object {
        fun open(context: Context) = WorkspaceSharePolicy(File(context.noBackupFilesDir, "workspace-private-documents"))
    }
}

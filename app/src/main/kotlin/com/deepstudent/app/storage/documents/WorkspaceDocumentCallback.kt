package com.deepstudent.app.storage.documents

import android.content.Intent
import android.os.ProxyFileDescriptorCallback
import android.system.ErrnoException
import android.system.OsConstants
import com.deepstudent.app.storage.AndroidOperationLogger
import java.io.File
import java.io.RandomAccessFile

/** A handle writes privately until a granted fsync/close; stale handles cannot overwrite a newer version. */
internal class WorkspaceDocumentCallback(
    private var info: WorkspaceDocumentInfo,
    private val access: DocumentAccess,
    private val grant: WorkspaceUriGrant,
    private val file: RandomAccessFile,
    private val stage: File,
    private val backend: WorkspaceDocumentsBackend,
    private val cleanup: WorkspaceDescriptorCleanup,
) : ProxyFileDescriptorCallback() {
    private val maxSize = info.maxSize
    private var dirty = access.write && (access.truncate || info.version == null)
    private var failed = false
    private var released = false

    override fun onGetSize(): Long = io("size", access.permission) { file.length() }

    override fun onRead(offset: Long, size: Int, data: ByteArray): Int = io("read", READ) {
        if (!access.read) throw ErrnoException("read", OsConstants.EBADF)
        file.seek(offset)
        file.read(data, 0, size).coerceAtLeast(0)
    }

    override fun onWrite(offset: Long, size: Int, data: ByteArray): Int = io("write", WRITE) {
        if (!access.write || failed) throw ErrnoException("write", OsConstants.EBADF)
        val start = if (access.append) file.length() else offset
        if (start < 0 || size < 0 || size > data.size || start > maxSize.toLong() - size) {
            failed = true
            throw ErrnoException("write", OsConstants.EFBIG)
        }
        file.seek(start)
        file.write(data, 0, size)
        dirty = dirty || size > 0
        size
    }

    override fun onFsync() {
        if (access.write) io("fsync", WRITE) { commit() }
    }

    override fun onRelease() {
        if (released) return
        try {
            if (dirty && !failed) onFsync()
        } catch (failure: Exception) {
            AndroidOperationLogger.warn("workspace.documents.close_uncommitted")
        } finally {
            released = true
            runCatching { file.close() }
            stage.delete()
            cleanup.release()
        }
    }

    private fun commit() {
        if (failed) throw ErrnoException("fsync", OsConstants.EIO)
        if (!dirty) return
        file.fd.sync()
        val updated = backend.write(info, stage)
        dirty = false
        if (updated != info) runCatching { cleanup.notify(info.key) }
        info = updated
    }

    private inline fun <T> io(stage: String, permission: Int, crossinline action: () -> T): T = try {
        grant.access(permission) { action() }
    } catch (denied: SecurityException) {
        if (permission == WRITE) failed = true
        throw ErrnoException(stage, OsConstants.EACCES)
    } catch (failure: ErrnoException) {
        throw failure
    } catch (failure: Exception) {
        if (permission == WRITE) failed = true
        AndroidOperationLogger.warn("workspace.documents.descriptor_failed")
        throw ErrnoException(stage, OsConstants.EIO)
    }

    private companion object {
        const val READ = Intent.FLAG_GRANT_READ_URI_PERMISSION
        const val WRITE = Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    }
}

package com.deepstudent.app.storage.documents

import android.content.Context
import android.os.CancellationSignal
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.storage.StorageManager
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.Semaphore

/** Scratch is provider-owned and never returned as a plaintext filesystem descriptor. */
internal class WorkspaceDocumentDescriptors(
    context: Context,
    private val backend: WorkspaceDocumentsBackend,
    private val notify: (WorkspaceDocumentKey) -> Unit,
) {
    private val scratch = File(context.cacheDir.canonicalFile, "workspace-document-io").also { dir ->
        require(dir.canonicalFile == dir.absoluteFile && (dir.isDirectory || dir.mkdir())) { "scratch directory unavailable" }
        dir.listFiles()?.filter { it.name.startsWith("doc-") && it.name.endsWith(".tmp") }?.forEach { it.delete() }
    }
    private val limit = Semaphore(8)
    private val handler = Handler(HandlerThread("dstu-workspace-files").apply { start() }.looper)
    private val storage = requireNotNull(context.getSystemService(StorageManager::class.java))

    fun open(
        info: WorkspaceDocumentInfo,
        access: DocumentAccess,
        signal: CancellationSignal?,
        grant: WorkspaceUriGrant,
    ): ParcelFileDescriptor {
        reserveSlot(info, access)
        var temporary: File? = null
        var file: RandomAccessFile? = null
        try {
            val stage = File.createTempFile("doc-", ".tmp", scratch).also { temporary = it }
            populate(info, access, stage, signal)
            grant.check(access)
            val opened = RandomAccessFile(stage, if (access.write) "rw" else "r").also { file = it }
            val callback = WorkspaceDocumentCallback(
                info,
                access,
                grant,
                opened,
                stage,
                backend,
                WorkspaceDescriptorCleanup(notify, limit::release),
            )
            val mode = if (access.write) ParcelFileDescriptor.MODE_READ_WRITE else ParcelFileDescriptor.MODE_READ_ONLY
            return storage.openProxyFileDescriptor(mode, callback, handler)
        } catch (failure: Throwable) {
            runCatching { file?.close() }
            temporary?.delete()
            limit.release()
            throw failure
        }
    }

    private fun reserveSlot(info: WorkspaceDocumentInfo, access: DocumentAccess) {
        if (!access.truncate && info.size !in 0..info.maxSize.toLong()) throw IOException("document exceeds the open budget")
        if (!limit.tryAcquire()) throw IOException("too many open workspace documents")
    }

    private fun populate(info: WorkspaceDocumentInfo, access: DocumentAccess, stage: File, signal: CancellationSignal?) {
        if (!access.truncate) {
            stage.outputStream().use { output ->
                backend.read(info, output) { signal?.throwIfCanceled() }
            }
        }
        signal?.throwIfCanceled()
    }
}

internal data class WorkspaceDescriptorCleanup(val notify: (WorkspaceDocumentKey) -> Unit, val release: () -> Unit)

package com.deepstudent.app.backup

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import com.deepstudent.platform.backupzip.CheckedZipArchive
import com.deepstudent.services.backup.api.RestorePlan
import com.deepstudent.services.backup.backend.ArchivePreflight
import java.io.File
import java.io.IOException
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal fun interface BackupPreviewReader {
    suspend fun read(source: Uri): RestorePlan
}

/** A URI is used only for this user-requested read. No DataSpace or execution port is available here. */
internal class SafBackupPreviewReader(private val context: Context) : BackupPreviewReader {
    override suspend fun read(source: Uri): RestorePlan = withContext(Dispatchers.IO) {
        val trace = BackupPreviewTrace()
        val checkCancellation = { coroutineContext.ensureActive() }
        trace.log("read", "started")
        val owned = Files.createTempFile(context.cacheDir.toPath(), "backup-preview-", ".zip").toFile()
        try {
            copyBackupSource(context, source, owned, checkCancellation)
            trace.log("inspect", "started")
            val plan = CheckedZipArchive(owned).use {
                ArchivePreflight.inspect(it, owned.usableSpace, checkCancellation).plan
            }
            trace.log("inspect", if (plan.canStage) "verified" else "not_stageable", plan.problems.firstOrNull()?.code?.name,
                sourceKind = if (plan.legacyImport != null) "legacy_mobile" else "other")
            plan
        } catch (cancelled: CancellationException) {
            trace.log("inspect", "cancelled")
            throw cancelled
        } catch (failure: Exception) {
            trace.log("inspect", "failed", "ARCHIVE_IO")
            throw IOException("archive preview failed", failure)
        } finally {
            if (!owned.delete()) trace.log("cleanup", "failed", "SCRATCH_IO")
        }
    }

}

private const val MAX_ARCHIVE_BYTES = 20L * 1024 * 1024 * 1024
private const val HEADROOM_BYTES = 16L * 1024 * 1024

internal fun copyBackupSource(
    context: Context, source: Uri, dest: File, cancel: () -> Unit, durable: Boolean = false,
    maxBytes: Long = MAX_ARCHIVE_BYTES,
) {
    check(source.scheme == "content")
    val capacity = (checkNotNull(dest.parentFile).usableSpace - HEADROOM_BYTES).coerceIn(0, minOf(maxBytes, MAX_ARCHIVE_BYTES))
    val input = context.contentResolver.openInputStream(source) ?: throw IOException("archive unavailable")
    input.use { read ->
        FileOutputStream(dest).use { output ->
            copyBounded(read, output, capacity, cancel)
            if (durable) output.fd.sync()
        }
    }
}

internal fun copyBounded(read: java.io.InputStream, output: java.io.OutputStream, capacity: Long, cancel: () -> Unit): Long {
    val buffer = ByteArray(128 * 1024)
    var remaining = capacity
    while (true) {
        cancel()
        val count = read.read(buffer)
        if (count == -1) return capacity - remaining
        if (count == 0 || count > remaining) throw IOException("archive read exceeded its budget")
        output.write(buffer, 0, count)
        remaining -= count
    }
}

private class BackupPreviewTrace {
    private val id = UUID.randomUUID()
    private val t0 = SystemClock.elapsedRealtime()
    fun log(phase: String, state: String, code: String? = null, sourceKind: String = "unknown") {
        Log.i("DstuBackupPreview", "event=backup.preview owner=app.backup correlationId=$id phase=$phase " +
            "state=$state elapsedMs=${SystemClock.elapsedRealtime() - t0} code=${code ?: "none"} sourceKind=$sourceKind")
    }
}

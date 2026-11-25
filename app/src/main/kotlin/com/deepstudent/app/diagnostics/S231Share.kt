package com.deepstudent.app.diagnostics

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.util.UUID

/** A single sanitized cache file and a temporary, read-only system share grant. */
internal object S231Share {
    fun intent(context: Context, snapshot: S231Snapshot): Intent {
        val bytes = S231TextBundle.create(snapshot)
        val directory = File(context.cacheDir, "diagnostic-share")
        check(directory.isDirectory || directory.mkdirs())
        // Bound retained sanitized files without exposing any other cache directory through the provider.
        directory.listFiles()?.filter { it.isFile && it.name.startsWith("dstu-diagnostics-") }
            ?.sortedByDescending { it.lastModified() }?.drop(3)?.forEach { it.delete() }
        val file = File(directory, "dstu-diagnostics-${UUID.randomUUID()}.txt")
        file.outputStream().use { it.write(bytes) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.diagnostic-share", file)
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("diagnostics", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}

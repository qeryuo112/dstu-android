package com.deepstudent.app.environments

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.deepstudent.features.environments.api.RootfsArchiveSource
import kotlinx.coroutines.CompletableDeferred

private class ArchiveRequest {
    var pending: CompletableDeferred<RootfsArchiveSource?>? = null
}

/** SAF belongs to app/platform wiring, not to the feature UI or its presenter. */
@Composable
internal fun rememberEnvironmentArchivePicker(): suspend () -> RootfsArchiveSource? {
    val resolver = LocalContext.current.applicationContext.contentResolver
    val request = remember { ArchiveRequest() }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val result = request.pending ?: return@rememberLauncherForActivityResult
        request.pending = null
        if (uri == null) {
            result.complete(null)
        } else {
            try {
                resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                // This callback captures only a durable document handle; no stream is opened on the UI thread.
                result.complete(RootfsArchiveSource {
                    resolver.openInputStream(uri) ?: error("rootfs document is unavailable")
                })
            } catch (failure: Exception) {
                result.completeExceptionally(failure)
            }
        }
    }
    DisposableEffect(request) {
        onDispose { request.pending?.cancel(); request.pending = null }
    }
    return remember(launcher, request) {
        {
            check(request.pending == null) { "rootfs document picker is already open" }
            val result = CompletableDeferred<RootfsArchiveSource?>()
            request.pending = result
            try {
                launcher.launch(arrayOf("application/x-xz", "application/x-tar", "application/octet-stream"))
                result.await()
            } finally {
                if (request.pending === result) request.pending = null
            }
        }
    }
}

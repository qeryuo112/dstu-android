package com.deepstudent.app.workspace

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.deepstudent.features.documents.ui.PdfPanel
import com.deepstudent.features.workspace.api.PanelInstance
import com.deepstudent.features.workspace.api.ResourceKey
import kotlinx.coroutines.launch

@Composable
internal fun WorkspacePdfEntry(ports: WorkspacePorts, panel: PanelInstance) {
    val resolver = LocalContext.current.contentResolver
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            scope.launch { ports.workspace.updateResource(panel.id, ResourceKey("pdf", uri.toString())) }
        }
    }
    PdfPanel(ports.pdf, panel.resource?.id, { picker.launch(arrayOf("application/pdf")) })
}

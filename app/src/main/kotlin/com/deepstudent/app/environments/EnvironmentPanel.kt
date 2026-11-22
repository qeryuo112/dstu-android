package com.deepstudent.app.environments

import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.deepstudent.features.environments.api.EnvironmentManager
import com.deepstudent.features.environments.api.RootfsArchiveSource
import com.deepstudent.features.environments.data.HttpsRootfsArchiveSource
import com.deepstudent.features.environments.ui.EnvironmentsRoute
import kotlinx.coroutines.CancellationException

private sealed interface EnvironmentEntryState {
    data object Loading : EnvironmentEntryState
    data object Failed : EnvironmentEntryState
    data class Ready(val manager: EnvironmentManager) : EnvironmentEntryState
}

@Composable
internal fun EnvironmentPanel(
    loadManager: suspend () -> EnvironmentManager,
    onClose: () -> Unit,
    archivePicker: (suspend () -> RootfsArchiveSource?)? = null,
) {
    var attempt by remember { mutableStateOf(0) }
    val entry by produceState<EnvironmentEntryState>(EnvironmentEntryState.Loading, loadManager, attempt) {
        value = try {
            EnvironmentEntryState.Ready(loadManager())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Log.w("DstuEnvironmentEntry", "event=owner-open-failed type=${failure.javaClass.simpleName}")
            EnvironmentEntryState.Failed
        }
    }
    val safPicker = rememberEnvironmentArchivePicker()
    // This is only a lazy handle. The worker, never Compose, opens the HTTPS connection.
    val downloadSource = remember { HttpsRootfsArchiveSource() }
    when (val current = entry) {
        EnvironmentEntryState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.testTag("environment-entry-loading"))
        }
        EnvironmentEntryState.Failed -> TextButton(onClick = { attempt++ }, modifier = Modifier.testTag("environment-entry-retry")) {
            Text("无法打开环境安装，重试")
        }
        is EnvironmentEntryState.Ready -> EnvironmentsRoute(
            current.manager, archivePicker ?: safPicker, onClose, downloadSource = downloadSource,
        )
    }
}

package com.deepstudent.app.execution

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
import com.deepstudent.app.storage.AndroidOperationLogger
import com.deepstudent.features.execution.ui.ShizukuExecutionRoute
import com.deepstudent.services.execution.api.ExecutionSystem
import kotlinx.coroutines.CancellationException

private sealed interface ExecutionEntryState {
    data object Loading : ExecutionEntryState
    data object Failed : ExecutionEntryState
    data class Ready(val system: ExecutionSystem) : ExecutionEntryState
}

/** Disposing this panel never closes the application-owned execution system. */
@Composable
internal fun ExecutionPanel(loadSystem: (suspend () -> ExecutionSystem)?) {
    if (loadSystem == null) {
        Text("Shizuku 执行未配置")
        return
    }
    var attempt by remember { mutableStateOf(0) }
    val entry by produceState<ExecutionEntryState>(ExecutionEntryState.Loading, loadSystem, attempt) {
        value = ExecutionEntryState.Loading
        value = try {
            ExecutionEntryState.Ready(loadSystem())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            AndroidOperationLogger.warn("execution.entry.open_failed")
            ExecutionEntryState.Failed
        }
    }
    when (val current = entry) {
        ExecutionEntryState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.testTag("execution-entry-loading"))
        }
        ExecutionEntryState.Failed -> TextButton(
            onClick = { attempt++ },
            modifier = Modifier.testTag("execution-entry-retry"),
        ) { Text("无法打开 Shizuku，重试") }
        is ExecutionEntryState.Ready -> ShizukuExecutionRoute(current.system)
    }
}

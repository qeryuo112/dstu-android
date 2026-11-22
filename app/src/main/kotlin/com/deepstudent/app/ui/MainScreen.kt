package com.deepstudent.app.ui

import androidx.activity.compose.ReportDrawnWhen
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.ExperimentalComposeUiApi
import com.deepstudent.app.BuildConfig
import com.deepstudent.app.backup.BackupRestoreUiStatus
import com.deepstudent.design.DstuTheme
import kotlinx.coroutines.CancellationException
import com.deepstudent.app.workspace.WorkspacePorts
import com.deepstudent.platform.database.MigrationMaintenanceException
import com.deepstudent.platform.database.MigrationState
import com.deepstudent.platform.database.MigrationStatus
import com.deepstudent.platform.storage.DataSpaceError
import com.deepstudent.platform.storage.DataSpaceException
import kotlinx.coroutines.flow.StateFlow
import com.deepstudent.app.graph.MigrationRetryState
import com.deepstudent.app.workspace.FourPanelWorkspace
import com.deepstudent.features.workspace.api.WorkspaceStatus

private sealed interface NotesStartup {
    data object Loading : NotesStartup
    data object Failed : NotesStartup
    data class DataSpaceUnavailable(val error: DataSpaceError) : NotesStartup
    data class Maintenance(val status: MigrationStatus, val previousGeneration: Long) : NotesStartup
    data class Ready(val ports: WorkspacePorts) : NotesStartup
}

@Composable
@OptIn(ExperimentalComposeUiApi::class)
internal fun MainScreen(
    loadWorkspace: suspend () -> WorkspacePorts,
    retryState: StateFlow<MigrationRetryState>,
    retryMigration: () -> Unit,
    restoreStatus: StateFlow<BackupRestoreUiStatus>? = null,
) {
    val model = rememberStartup(loadWorkspace, retryState)
    val startup = model.startup
    val retry = model.retry
    val workspace = (startup as? NotesStartup.Ready)?.ports?.workspace
    val workspaceReady = workspace?.state?.collectAsState()?.value?.status == WorkspaceStatus.Ready
    // TTFD is the first draw with the restored workspace, not the initial loading frame.
    ReportDrawnWhen { workspaceReady }
    val markReady = BuildConfig.APPLICATION_ID == "com.deepstudent.app.benchmark" && workspaceReady
    DstuTheme {
        Surface(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().safeDrawingPadding().then(if (markReady)
                Modifier.semantics { testTagsAsResourceId = true }.testTag("s127-workspace-ready") else Modifier),
                contentAlignment = Alignment.Center) {
                when (val current = startup) {
                    NotesStartup.Loading -> StartupProgress(restoreStatus)
                    NotesStartup.Failed -> TextButton(onClick = model.restart) { Text("无法恢复工作区，重试") }
                    is NotesStartup.DataSpaceUnavailable -> DataSpaceMaintenanceScreen(current.error, model.restart)
                    is NotesStartup.Maintenance -> MigrationMaintenanceScreen(
                        current.latestFailure(retry), retry is MigrationRetryState.Running, retryMigration,
                    )
                    is NotesStartup.Ready -> FourPanelWorkspace(current.ports)
                }
            }
        }
    }
}

@Composable
private fun StartupProgress(restoreStatus: StateFlow<BackupRestoreUiStatus>?) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator()
        if (restoreStatus?.collectAsState()?.value == BackupRestoreUiStatus.APPLYING) {
            Text("正在校验和恢复备份，完成前不会打开业务数据库。")
        }
    }
}

private class StartupModel(val startup: NotesStartup, val retry: MigrationRetryState, val restart: () -> Unit)

@Composable
private fun rememberStartup(
    loadWorkspace: suspend () -> WorkspacePorts,
    retryState: StateFlow<MigrationRetryState>,
): StartupModel {
    var attempt by remember { mutableStateOf(0) }
    val retry by retryState.collectAsState()
    val startup by produceState<NotesStartup>(NotesStartup.Loading, attempt) {
        val beginning = retryState.value
        val previous = beginning.generation - if (beginning is MigrationRetryState.Running) 1 else 0
        value = NotesStartup.Loading
        value = loadStartup(loadWorkspace, previous)
    }
    LaunchedEffect(startup, retry) {
        val failure = startup as? NotesStartup.Maintenance ?: return@LaunchedEffect
        val completed = retry as? MigrationRetryState.Finished ?: return@LaunchedEffect
        if (completed.generation > failure.previousGeneration && completed.result.state == MigrationState.READY) attempt++
    }
    return StartupModel(startup, retry) { attempt++ }
}

private suspend fun loadStartup(loadWorkspace: suspend () -> WorkspacePorts, previous: Long): NotesStartup = try {
    NotesStartup.Ready(loadWorkspace())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: MigrationMaintenanceException) {
    NotesStartup.Maintenance(failure.status, previous)
} catch (failure: DataSpaceException) {
    NotesStartup.DataSpaceUnavailable(failure.error)
} catch (_: Exception) {
    NotesStartup.Failed
}

private fun NotesStartup.Maintenance.latestFailure(retry: MigrationRetryState): MigrationStatus {
    if (retry.generation <= previousGeneration) return status
    return when (retry) {
        is MigrationRetryState.Running -> retry.previous
        is MigrationRetryState.Finished -> retry.result.takeIf { it.state == MigrationState.FAILED } ?: status
        is MigrationRetryState.Idle -> status
    }
}

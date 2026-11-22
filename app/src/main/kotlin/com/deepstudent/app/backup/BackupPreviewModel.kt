package com.deepstudent.app.backup

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.deepstudent.services.backup.api.RestorePlan
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal sealed interface BackupPreviewState {
    data object Initial : BackupPreviewState
    data object Reading : BackupPreviewState
    data object Unreadable : BackupPreviewState
    data class Report(val plan: RestorePlan) : BackupPreviewState
}

internal class BackupPreviewModel(
    private val reader: BackupPreviewReader,
    private val restore: BackupRestoreCoordinator,
) : ViewModel() {
    private val mutable = MutableStateFlow<BackupPreviewState>(BackupPreviewState.Initial)
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private var selected: Uri? = null

    fun open(uri: Uri) {
        job?.cancel()
        selected = uri
        mutable.value = BackupPreviewState.Reading
        job = viewModelScope.launch {
            mutable.value = try {
                BackupPreviewState.Report(reader.read(uri))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                BackupPreviewState.Unreadable
            }
        }
    }

    fun requestRestore() {
        val uri = selected ?: return
        val plan = (state.value as? BackupPreviewState.Report)?.plan ?: return
        if (plan.canStage) viewModelScope.launch { restore.schedule(uri, plan) }
    }

    fun cancel() {
        job?.cancel()
        selected = null
        mutable.value = BackupPreviewState.Initial
    }
}

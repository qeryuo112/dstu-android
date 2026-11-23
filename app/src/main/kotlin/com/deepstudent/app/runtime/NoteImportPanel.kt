package com.deepstudent.app.runtime

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.deepstudent.app.R
import com.deepstudent.core.common.id.TaskId
import com.deepstudent.services.runtime.api.RuntimeState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Suppress("ktlint:standard:function-naming") // Compose entry points use PascalCase.
@Composable
internal fun NoteImportPanel(actions: NoteImportActions) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var currentTask by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by rememberSaveable { mutableStateOf<Int?>(null) }
    val submit: (Boolean) -> Unit = { notifications ->
        scope.launch {
            try {
                val result = actions.start(url, title, notifications)
                currentTask = result.taskId.value
                message = if (result.background) null else R.string.note_import_app_only
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                message = R.string.note_import_submit_error
            } finally {
                busy = false
            }
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission(), submit)
    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.note_import_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.note_import_explanation))
        ImportField(title, { title = it }, "note-import-title", R.string.note_import_name, !busy)
        ImportField(url, { url = it }, "note-import-url", R.string.note_import_address, !busy)
        Button(
            onClick = {
                busy = true
                if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
                ) {
                    permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    submit(true)
                }
            },
            enabled = !busy && actions.accepts(url, title),
            modifier = Modifier.testTag("note-import-submit"),
        ) { Text(stringResource(R.string.note_import_start)) }
        message?.let { Text(stringResource(it)) }
        currentTask?.let { NoteImportStatus(actions, TaskId.parse(it)) }
    }
}

@Suppress("ktlint:standard:function-naming") // Compose entry points use PascalCase.
@Composable
private fun ImportField(value: String, onValue: (String) -> Unit, tag: String, label: Int, enabled: Boolean) {
    OutlinedTextField(
        value,
        onValue,
        Modifier.fillMaxWidth().testTag(tag),
        enabled = enabled,
        label = { Text(stringResource(label)) },
        singleLine = true,
    )
}

@Suppress("ktlint:standard:function-naming") // Compose entry points use PascalCase.
@Composable
private fun NoteImportStatus(actions: NoteImportActions, taskId: TaskId) {
    val changes = remember(actions, taskId) { actions.observe(taskId) }
    val state by changes.collectAsState(null)
    val scope = rememberCoroutineScope()
    Text(stringResource(R.string.note_import_task_id, taskId.value), Modifier.testTag("note-import-task"))
    val status = when (state) {
        null, RuntimeState.CREATED -> R.string.note_import_queued
        RuntimeState.RUNNING -> R.string.note_import_running
        RuntimeState.COMPLETED -> R.string.note_import_completed
        RuntimeState.CANCELLED -> R.string.note_import_cancelled
        RuntimeState.FAILED -> R.string.note_import_failed
        RuntimeState.INTERRUPTED -> R.string.note_import_interrupted
        RuntimeState.PAUSED, RuntimeState.WAITING_APPROVAL -> R.string.note_import_paused
    }
    Text(stringResource(status))
    if (state?.terminal == false) {
        TextButton(onClick = { scope.launch { actions.cancel(taskId) } }) {
            Text(stringResource(R.string.note_import_cancel))
        }
    }
}

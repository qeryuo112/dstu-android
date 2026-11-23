package com.deepstudent.app.scheduling

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.deepstudent.app.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Suppress("ktlint:standard:function-naming") // Compose entry points use PascalCase.
@Composable
internal fun SchedulePanel(schedules: MainSchedules) {
    val rows by schedules.rows.collectAsState(emptyList())
    val scope = rememberCoroutineScope()
    var failed by remember { mutableStateOf(false) }
    val dispatch: (suspend () -> Unit) -> Unit = { action ->
        scope.launch {
            try {
                failed = false
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                failed = true
            }
        }
    }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.schedules_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.schedules_explanation))
        ScheduleForm { draft, after, repeat ->
            dispatch { schedules.create(draft, Instant.now().plusSeconds(after * 60L), repeat) }
        }
        if (failed) Text(stringResource(R.string.schedules_action_failed))
        for (row in rows) {
            ScheduleRow(row, { dispatch { schedules.runNow(row.id) } }, { dispatch { schedules.cancel(row.id) } })
        }
    }
}

@Suppress("ktlint:standard:function-naming") // Compose entry points use PascalCase.
@Composable
private fun ScheduleForm(save: (ScheduledDraft, Int, Int) -> Unit) {
    val context = LocalContext.current
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var minutes by remember { mutableStateOf("15") }
    var repeat by remember { mutableStateOf("") }
    val period = if (repeat.isBlank()) 0 else repeat.toIntOrNull()?.takeIf { it in 15..1440 }
    val delay = minutes.toIntOrNull()?.takeIf { it in 1..10_080 }
    val valid = title.isNotBlank() && title.length <= 200 && body.length <= 16_000 && delay != null
    val notification = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && valid) save(ScheduledDraft(title.trim(), body, SchedulePurpose.REMINDER), requireNotNull(delay), 0)
    }
    OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.schedules_name)) })
    OutlinedTextField(body, { body = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.schedules_body)) })
    OutlinedTextField(minutes, { minutes = it }, label = { Text(stringResource(R.string.schedules_after)) }, singleLine = true)
    OutlinedTextField(repeat, { repeat = it }, label = { Text(stringResource(R.string.schedules_repeat)) }, singleLine = true)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(enabled = valid && period != null, onClick = {
            save(ScheduledDraft(title.trim(), body, SchedulePurpose.NOTE), requireNotNull(delay), requireNotNull(period))
        }) {
            Text(stringResource(R.string.schedules_note))
        }
        TextButton(enabled = valid, onClick = {
            if (needsNotificationPermission(context)) {
                notification.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                save(ScheduledDraft(title.trim(), body, SchedulePurpose.REMINDER), requireNotNull(delay), 0)
            }
        }) { Text(stringResource(R.string.schedules_reminder)) }
    }
}

private fun needsNotificationPermission(context: android.content.Context): Boolean = Build.VERSION.SDK_INT >= 33 &&
    context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED

@Suppress("ktlint:standard:function-naming") // Compose entry points use PascalCase.
@Composable
private fun ScheduleRow(row: LocalSchedule, run: () -> Unit, remove: () -> Unit) {
    val date = remember(row.due) {
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withZone(ZoneId.systemDefault()).format(row.due)
    }
    Text(row.draft.title, style = MaterialTheme.typography.titleSmall)
    Text(stringResource(if (row.enabled) R.string.schedules_pending else R.string.schedules_settled, date))
    Row {
        if (row.enabled && row.draft.purpose == SchedulePurpose.NOTE) {
            TextButton(run, modifier = Modifier.testTag("schedule-run-${row.id.value}")) {
                Text(stringResource(R.string.schedules_run_now))
            }
        }
        TextButton(remove) { Text(stringResource(R.string.schedules_remove)) }
    }
}

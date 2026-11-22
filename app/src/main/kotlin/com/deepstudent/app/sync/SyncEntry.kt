package com.deepstudent.app.sync

import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.deepstudent.features.sync.ui.SyncController
import com.deepstudent.features.sync.ui.SyncPanel
import kotlinx.coroutines.CancellationException

private sealed interface Entry {
    data object Loading : Entry
    data object Failed : Entry
    data class Ready(val controller: SyncController) : Entry
}

@Composable
internal fun SyncEntry(load: (suspend () -> SyncController)?) {
    if (load == null) {
        Text("同步配置不可用")
        return
    }
    var attempt by remember { mutableIntStateOf(0) }
    val entry by produceState<Entry>(Entry.Loading, load, attempt) {
        value = Entry.Loading
        value = try {
            Entry.Ready(load())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Entry.Failed
        }
    }
    when (val current = entry) {
        Entry.Loading -> CircularProgressIndicator()
        Entry.Failed -> TextButton(onClick = { attempt++ }) { Text("无法打开同步配置，重试") }
        is Entry.Ready -> SyncPanel(current.controller)
    }
}

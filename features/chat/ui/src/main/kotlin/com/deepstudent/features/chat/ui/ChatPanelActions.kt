package com.deepstudent.features.chat.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.deepstudent.core.common.id.TaskId
import com.deepstudent.features.chat.api.ChatStreamRequest
import com.deepstudent.features.chat.api.ChatStreams
import kotlinx.coroutines.CancellationException

internal class ChatPanelActions(private val streams: ChatStreams, private val onTask: (TaskId) -> Unit) {
    var submitting by mutableStateOf(false)
        private set
    var failed by mutableStateOf(false)
        private set

    suspend fun submit(running: Boolean, taskId: TaskId?, endpoint: String, prompt: String) {
        try {
            failed = false
            if (running) taskId?.let { streams.cancel(it) }
            else {
                submitting = true
                onTask(streams.start(ChatStreamRequest(endpoint, prompt)))
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failed = true }
        finally { submitting = false }
    }
}

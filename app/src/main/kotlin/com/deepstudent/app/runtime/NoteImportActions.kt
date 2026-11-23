package com.deepstudent.app.runtime

import com.deepstudent.core.common.id.TaskId
import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.platform.background.ForegroundDutyStarter
import com.deepstudent.platform.background.ForegroundStartResult
import com.deepstudent.services.runtime.api.RuntimeState
import com.deepstudent.services.runtime.api.TaskSubmission
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URI

internal data class NoteImportStart(val taskId: TaskId, val background: Boolean)

/** The visible user action starts an app-owned task; neither the panel nor its notification executes it. */
internal class NoteImportActions(
    private val runtime: suspend () -> MainRuntimeResources,
    private val foreground: ForegroundDutyStarter,
    private val allowFixtureHttp: Boolean = false,
) {
    fun accepts(url: String, title: String): Boolean = title.trim().length in 1..200 && validImportUrl(url.trim(), allowFixtureHttp)

    suspend fun start(url: String, title: String, allowForeground: Boolean = true): NoteImportStart {
        val source = url.trim()
        val name = title.trim()
        require(accepts(source, name))
        val controller = runtime().controller
        val handle = controller.submit(
            TaskSubmission(
                MainRuntimeResources.NOTE_IMPORT,
                JsonValue.obj("url" to JsonValue.text(source), "title" to JsonValue.text(name)),
            ),
        )
        if (!allowForeground) return NoteImportStart(handle.taskId, false)
        val running = withTimeoutOrNull(FOREGROUND_WAIT_MS) {
            controller.observeSnapshots(handle.taskId).filterNotNull().first { it.state != RuntimeState.CREATED }
        }?.state == RuntimeState.RUNNING
        val promoted = running && foreground.startDataSync(handle.taskId) is ForegroundStartResult.Started
        return NoteImportStart(handle.taskId, promoted)
    }

    fun observe(taskId: TaskId): Flow<RuntimeState?> = flow {
        emitAll(runtime().controller.observeSnapshots(taskId).map { it?.state })
    }

    suspend fun cancel(taskId: TaskId) {
        val controller = runtime().controller
        val current = controller.snapshot(taskId) ?: return
        controller.cancelIfGeneration(taskId, current.ownership.generation)
    }

    private companion object {
        const val FOREGROUND_WAIT_MS = 3_000L
    }
}

internal fun validImportUrl(source: String, allowFixtureHttp: Boolean): Boolean {
    if (source.length > 2048) return false
    val uri = runCatching { URI(source) }.getOrNull() ?: return false
    val host = uri.host ?: return false
    return uri.hasImportAuthority() && (
        uri.scheme.equals("https", ignoreCase = true) ||
            (allowFixtureHttp && uri.scheme == "http" && host == "127.0.0.1")
        )
}

private fun URI.hasImportAuthority(): Boolean = rawUserInfo == null && rawFragment == null && (port == -1 || port in 1..65535)

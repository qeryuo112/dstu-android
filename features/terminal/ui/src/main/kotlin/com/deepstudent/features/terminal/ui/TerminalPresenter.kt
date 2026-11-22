package com.deepstudent.features.terminal.ui

import com.deepstudent.features.terminal.api.TerminalCommandException
import com.deepstudent.features.terminal.api.TerminalCommands
import com.deepstudent.features.terminal.api.TerminalFailure
import com.deepstudent.features.terminal.api.TerminalQuery
import com.deepstudent.features.terminal.api.TerminalSessionId
import com.deepstudent.features.terminal.api.TerminalSessionSummary
import com.deepstudent.features.terminal.api.TerminalSize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * UI state owner. It never owns a process: leaving the route cancels observation only.
 */
class TerminalPresenter(
    private val query: TerminalQuery,
    private val commands: TerminalCommands,
    private val scope: CoroutineScope,
) {
    private val mutable = MutableStateFlow(TerminalUiState())
    private var sessionJob: Job? = null
    private val sessionsJob = scope.launch {
        query.sessions.collect(::applySessions)
    }

    val state: StateFlow<TerminalUiState> = mutable.asStateFlow()

    val actions = TerminalActions(
        create = ::create,
        select = ::select,
        write = ::write,
        interrupt = ::interrupt,
        resize = ::resize,
        terminate = ::terminate,
        close = ::close,
        dismissFailure = { mutable.value = mutable.value.copy(failure = null) },
    )

    fun stop() {
        sessionsJob.cancel()
        sessionJob?.cancel()
    }

    private fun applySessions(items: List<TerminalSessionSummary>) {
        val selected = mutable.value.selectedId?.takeIf { id -> items.any { it.id == id } }
        mutable.value = mutable.value.copy(sessions = items)
        if (selected != null) return
        items.firstOrNull()?.id?.let(::select) ?: clearSelection()
    }

    private fun select(id: TerminalSessionId) {
        if (mutable.value.selectedId == id) return
        sessionJob?.cancel()
        mutable.value = mutable.value.copy(selectedId = id, snapshot = null)
        sessionJob = scope.launch {
            query.observe(id)?.collect { snapshot ->
                if (mutable.value.selectedId == id) {
                    mutable.value = mutable.value.copy(snapshot = snapshot)
                }
            }
        }
    }

    private fun clearSelection() {
        sessionJob?.cancel()
        sessionJob = null
        mutable.value = mutable.value.copy(selectedId = null, snapshot = null)
    }

    private fun create() {
        if (mutable.value.busy) return
        mutable.value = mutable.value.copy(busy = true, failure = null)
        launch {
            val id = commands.open()
            mutable.value = mutable.value.copy(busy = false)
            select(id)
        }
    }

    private fun write(text: String) {
        val id = mutable.value.selectedId ?: return
        launch { commands.write(id, text) }
    }

    private fun interrupt() {
        val id = mutable.value.selectedId ?: return
        launch { commands.interrupt(id) }
    }

    private fun resize(size: TerminalSize) {
        val id = mutable.value.selectedId ?: return
        if (mutable.value.snapshot?.screen?.size == size) return
        launch { commands.resize(id, size) }
    }

    private fun terminate() {
        val id = mutable.value.selectedId ?: return
        launch { commands.terminate(id) }
    }

    private fun close(id: TerminalSessionId) {
        launch {
            commands.close(id)
            if (mutable.value.selectedId == id) clearSelection()
        }
    }

    private fun launch(block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: TerminalCommandException) {
                mutable.value = mutable.value.copy(busy = false, failure = error.failure.toUi())
            } catch (_: Exception) {
                mutable.value = mutable.value.copy(busy = false, failure = TerminalUiFailure.UNKNOWN)
            }
        }
    }

    private fun TerminalFailure.toUi(): TerminalUiFailure = when (this) {
        TerminalFailure.START_FAILED -> TerminalUiFailure.START_FAILED
        TerminalFailure.IO_FAILED -> TerminalUiFailure.IO_FAILED
        TerminalFailure.RESIZE_FAILED -> TerminalUiFailure.RESIZE_FAILED
        TerminalFailure.STOP_FAILED -> TerminalUiFailure.STOP_FAILED
        TerminalFailure.SESSION_CLOSED -> TerminalUiFailure.SESSION_CLOSED
        TerminalFailure.EXIT_STATUS_UNAVAILABLE -> TerminalUiFailure.UNKNOWN
    }
}

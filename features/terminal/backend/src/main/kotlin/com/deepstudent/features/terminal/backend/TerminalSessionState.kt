package com.deepstudent.features.terminal.backend

import com.deepstudent.features.terminal.api.TerminalFailure
import com.deepstudent.features.terminal.api.TerminalPhase
import com.deepstudent.features.terminal.api.TerminalSessionSnapshot
import com.deepstudent.features.terminal.api.TerminalSize
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class TerminalSessionState(
    private val screen: TerminalScreenPort,
    size: TerminalSize,
) {
    private val lock = Any()
    private val mutable = MutableStateFlow(
        TerminalSessionSnapshot(TerminalPhase.STARTING, screen.snapshot()),
    )

    val state: StateFlow<TerminalSessionSnapshot> = mutable.asStateFlow()

    fun running() = synchronized(lock) {
        if (mutable.value.phase == TerminalPhase.STARTING) {
            mutable.value = mutable.value.copy(phase = TerminalPhase.RUNNING)
        }
    }

    fun output(text: String) = synchronized(lock) {
        screen.append(text)
        mutable.value = mutable.value.copy(screen = screen.snapshot())
    }

    fun resized(size: TerminalSize) = synchronized(lock) {
        screen.resize(size)
        mutable.value = mutable.value.copy(screen = screen.snapshot())
    }

    fun stopping() = synchronized(lock) {
        if (mutable.value.phase == TerminalPhase.RUNNING) {
            mutable.value = mutable.value.copy(phase = TerminalPhase.STOPPING)
        }
    }

    fun failed(failure: TerminalFailure) = synchronized(lock) {
        if (mutable.value.phase != TerminalPhase.EXITED) {
            mutable.value = mutable.value.copy(phase = TerminalPhase.FAILED, failure = failure)
        }
    }

    fun exited(code: Int) = synchronized(lock) {
        val current = mutable.value
        val statusFailure = if (code < 0) {
            current.failure ?: TerminalFailure.EXIT_STATUS_UNAVAILABLE
        } else {
            current.failure
        }
        mutable.value = current.copy(
            phase = if (statusFailure == null) TerminalPhase.EXITED else TerminalPhase.FAILED,
            exitCode = code.takeIf { it >= 0 },
            failure = statusFailure,
        )
    }
}

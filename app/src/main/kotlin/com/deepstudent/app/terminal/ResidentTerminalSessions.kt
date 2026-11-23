package com.deepstudent.app.terminal

import com.deepstudent.features.terminal.api.TerminalPhase
import com.deepstudent.features.terminal.api.TerminalSessionId
import com.deepstudent.features.terminal.api.TerminalSessions
import com.deepstudent.features.terminal.api.TerminalSize

/** Session ownership stays with the PTY service; the app only adds or removes residency. */
internal class ResidentTerminalSessions(private val delegate: TerminalSessions, private val residency: TerminalResidency) :
    TerminalSessions by delegate {
    override suspend fun open(size: TerminalSize): TerminalSessionId = delegate.open(size).also {
        residency.refresh()
    }

    override suspend fun close(id: TerminalSessionId) {
        residency.withdraw(id)
        delegate.close(id)
    }

    override suspend fun terminate(id: TerminalSessionId) {
        residency.withdraw(id)
        delegate.terminate(id)
    }

    override suspend fun write(id: TerminalSessionId, text: String) {
        residency.withInputWindow(id) { delegate.write(id, text) }
    }
}

internal fun TerminalSessions.isRunning(id: TerminalSessionId): Boolean = observe(id)?.value?.phase == TerminalPhase.RUNNING

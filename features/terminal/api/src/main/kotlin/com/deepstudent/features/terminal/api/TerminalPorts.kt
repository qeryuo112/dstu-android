package com.deepstudent.features.terminal.api

import kotlinx.coroutines.flow.StateFlow

/**
 * Commands own process mutation. No implementation type, file descriptor, PID or signal is
 * exposed through this contract.
 */
interface TerminalCommands {
    suspend fun open(size: TerminalSize = TerminalSize.Default): TerminalSessionId

    suspend fun write(id: TerminalSessionId, text: String)

    /** Sends the terminal's interrupt character (Ctrl+C) to the foreground process. */
    suspend fun interrupt(id: TerminalSessionId)

    suspend fun resize(id: TerminalSessionId, size: TerminalSize)

    /** Terminates the process but keeps the final screen and exit status observable. */
    suspend fun terminate(id: TerminalSessionId)

    /** Terminates, reaps and forgets the session. Safe to call more than once. */
    suspend fun close(id: TerminalSessionId)
}

interface TerminalQuery {
    val sessions: StateFlow<List<TerminalSessionSummary>>

    fun observe(id: TerminalSessionId): StateFlow<TerminalSessionSnapshot>?
}

interface TerminalSessions : TerminalCommands, TerminalQuery

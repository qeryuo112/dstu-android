package com.deepstudent.features.terminal.backend

import com.deepstudent.features.terminal.api.TerminalSessionId
import com.deepstudent.features.terminal.api.TerminalSessionSnapshot
import com.deepstudent.features.terminal.api.TerminalSessionSummary
import com.deepstudent.features.terminal.api.TerminalSessions
import com.deepstudent.features.terminal.api.TerminalSize
import com.deepstudent.features.terminal.api.TerminalCommandException
import com.deepstudent.features.terminal.api.TerminalFailure
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow

/**
 * Application-owned terminal service. The app graph creates one instance and keeps it alive
 * independently of Compose screens.
 */
class TerminalSessionService(
    processes: TerminalProcessFactory,
    screens: TerminalScreenFactory,
    ids: TerminalIdFactory = TerminalIdFactory { UUID.randomUUID().toString() },
) : TerminalSessions {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val registry = TerminalSessionRegistry(processes, screens, ids, scope)

    override val sessions: StateFlow<List<TerminalSessionSummary>> = registry.sessions

    override suspend fun open(size: TerminalSize): TerminalSessionId = registry.open(size)

    override fun observe(id: TerminalSessionId): StateFlow<TerminalSessionSnapshot>? = registry.observe(id)

    override suspend fun write(id: TerminalSessionId, text: String) = idSession(id).write(text)

    override suspend fun interrupt(id: TerminalSessionId) = idSession(id).interrupt()

    override suspend fun resize(id: TerminalSessionId, size: TerminalSize) = idSession(id).resize(size)

    private fun idSession(id: TerminalSessionId): OwnedTerminalSession =
        registry.session(id) ?: throw TerminalCommandException(TerminalFailure.SESSION_CLOSED)

    override suspend fun terminate(id: TerminalSessionId) = registry.terminate(id)

    override suspend fun close(id: TerminalSessionId) = registry.close(id)

    suspend fun shutdown() {
        registry.closeAll()
        scope.cancel()
    }
}

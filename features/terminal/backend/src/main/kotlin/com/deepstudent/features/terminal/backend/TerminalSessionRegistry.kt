package com.deepstudent.features.terminal.backend

import com.deepstudent.features.terminal.api.TerminalFailure
import com.deepstudent.features.terminal.api.TerminalCommandException
import com.deepstudent.features.terminal.api.TerminalSessionId
import com.deepstudent.features.terminal.api.TerminalSessionSnapshot
import com.deepstudent.features.terminal.api.TerminalSessionSummary
import com.deepstudent.features.terminal.api.TerminalSize
import java.util.LinkedHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

internal class TerminalSessionRegistry(
    private val processes: TerminalProcessFactory,
    private val screens: TerminalScreenFactory,
    private val ids: TerminalIdFactory,
    private val scope: CoroutineScope,
) {
    private val lock = Any()
    private val active = LinkedHashMap<TerminalSessionId, OwnedTerminalSession>()
    private val mutableSessions = MutableStateFlow<List<TerminalSessionSummary>>(emptyList())
    private var nextOrdinal = 1
    private var closed = false

    val sessions: StateFlow<List<TerminalSessionSummary>> = mutableSessions.asStateFlow()

    suspend fun open(size: TerminalSize): TerminalSessionId = withContext(Dispatchers.IO) {
        val id = TerminalSessionId(ids.next())
        val process = try {
            processes.start(size)
        } catch (error: Exception) {
            TerminalLog.failure("start-failed", id, error)
            throw TerminalCommandException(TerminalFailure.START_FAILED, error)
        }
        var owner: OwnedTerminalSession? = null
        try {
            val screen = screens.create(size)
            val ordinal = synchronized(lock) {
                check(!closed) { "Terminal service is closed" }
                nextOrdinal++
            }
            val session = OwnedTerminalSession(id, ordinal, size, process, screen, scope)
            owner = session
            session.start()
            val registered = synchronized(lock) {
                if (closed || active.containsKey(id)) false else {
                    active[id] = session
                    publishLocked()
                    true
                }
            }
            if (!registered) {
                session.close()
                throw TerminalCommandException(TerminalFailure.SESSION_CLOSED)
            }
            id
        } catch (error: Exception) {
            val session = owner
            if (session == null) closeQuietly(process) else session.close()
            if (error is TerminalCommandException) throw error
            TerminalLog.failure("start-failed", id, error)
            throw TerminalCommandException(TerminalFailure.START_FAILED, error)
        }
    }

    fun observe(id: TerminalSessionId): StateFlow<TerminalSessionSnapshot>? =
        synchronized(lock) { active[id]?.state }

    fun session(id: TerminalSessionId): OwnedTerminalSession? =
        synchronized(lock) { active[id] }

    suspend fun terminate(id: TerminalSessionId) {
        val session = synchronized(lock) { active[id] } ?: return
        session.terminate()
    }

    suspend fun close(id: TerminalSessionId) = withContext(NonCancellable) {
        val session = synchronized(lock) { active[id] } ?: return@withContext
        try {
            session.close()
        } finally {
            synchronized(lock) {
                active.remove(id)
                publishLocked()
            }
        }
    }

    suspend fun closeAll() = withContext(NonCancellable) {
        val all = synchronized(lock) {
            closed = true
            active.values.toList()
        }
        all.forEach { it.close() }
        synchronized(lock) {
            active.clear()
            publishLocked()
        }
    }

    private fun publishLocked() {
        mutableSessions.value = active.values.map { TerminalSessionSummary(it.id, it.ordinal) }
    }

    private fun closeQuietly(process: TerminalProcessPort) {
        try {
            process.close()
        } catch (error: Exception) {
            TerminalLog.failure("start-cleanup-failed", null, error)
        }
    }
}

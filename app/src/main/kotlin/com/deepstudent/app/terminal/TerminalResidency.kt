package com.deepstudent.app.terminal

import com.deepstudent.app.power.BackgroundPreferences
import com.deepstudent.features.terminal.api.TerminalPhase
import com.deepstudent.features.terminal.api.TerminalSessionId
import com.deepstudent.features.terminal.api.TerminalSessions
import com.deepstudent.platform.background.ContinuousDuty
import com.deepstudent.platform.background.DutyRegistry
import com.deepstudent.platform.background.ForegroundDutyStarter
import com.deepstudent.platform.background.ForegroundPurpose
import com.deepstudent.platform.background.WakeLockController
import com.deepstudent.platform.background.WakePhase
import com.deepstudent.platform.background.WakeSegmentResult
import com.deepstudent.platform.background.wakeOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Keep only real, live PTY sessions resident. A previous process's commands are never replayed. */
internal class TerminalResidency(
    private val scope: CoroutineScope,
    private val preferences: BackgroundPreferences,
    private val registry: DutyRegistry,
    private val starter: ForegroundDutyStarter,
    private val wake: WakeLockController,
) {
    private val operations = Mutex()
    private val watchers = mutableMapOf<TerminalSessionId, Job>()

    @Volatile private var sessions: TerminalSessions? = null
    val enabled: StateFlow<Boolean> = preferences.enabled.stateIn(scope, SharingStarted.Eagerly, false)
    val duties get() = registry.continuousDuties

    fun attach(value: TerminalSessions): TerminalSessions {
        check(sessions == null) { "terminal residency already attached" }
        sessions = value
        return ResidentTerminalSessions(value, this)
    }

    suspend fun setEnabled(value: Boolean) = operations.withLock {
        preferences.setEnabled(value)
        if (value) activateRunning() else withdrawAll()
    }

    suspend fun refresh() = operations.withLock {
        if (preferences.enabled.first()) activateRunning() else withdrawAll()
    }

    fun eligible(duty: ContinuousDuty): Boolean =
        duty.purpose == ForegroundPurpose.TERMINAL && sessions?.isRunning(TerminalSessionId(duty.ownerId)) == true

    suspend fun withdraw(id: TerminalSessionId) = operations.withLock {
        watchers.remove(id)?.cancel()
        withdrawResources(id)
    }

    fun releasePower() = wake.releaseAll()

    internal fun hasActivePowerWindow(): Boolean = wake.activeOwners().isNotEmpty()

    suspend fun withInputWindow(id: TerminalSessionId, write: suspend () -> Unit) {
        val duty = duties.value.firstOrNull { it.ownerId == id.value }
        if (duty == null || !eligible(duty)) {
            write()
            return
        }
        val result = wake.withSegment(wakeOwner(duty), WakePhase.WORK, write)
        if (result is WakeSegmentResult.Denied) write()
    }

    private suspend fun activateRunning() {
        val owner = sessions ?: return
        for (session in owner.sessions.value) activate(owner, session.id)
    }

    private suspend fun activate(owner: TerminalSessions, id: TerminalSessionId) {
        val state = owner.observe(id) ?: return
        if (state.value.phase != TerminalPhase.RUNNING || duties.value.any { it.ownerId == id.value }) return
        if (starter.startTerminal(id.value) !is com.deepstudent.platform.background.ForegroundStartResult.Started) return
        watchers.remove(id)?.cancel()
        watchers[id] = scope.launch {
            state.first { it.phase != TerminalPhase.RUNNING }
            operations.withLock {
                watchers.remove(id)
                withdrawResources(id)
            }
        }
    }

    private fun withdrawAll() {
        watchers.values.forEach(Job::cancel)
        watchers.clear()
        duties.value.forEach { withdrawResources(TerminalSessionId(it.ownerId)) }
    }

    private fun withdrawResources(id: TerminalSessionId) {
        starter.retireTerminal(id.value)
        wake.release(wakeOwner(ContinuousDuty(id.value, ForegroundPurpose.TERMINAL)))
    }
}

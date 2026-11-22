package com.deepstudent.app.graph

import com.deepstudent.platform.database.MigrationState
import com.deepstudent.platform.database.MigrationStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal sealed interface MigrationRetryState {
    val generation: Long
    data class Idle(override val generation: Long = 0) : MigrationRetryState
    data class Running(override val generation: Long, val previous: MigrationStatus) : MigrationRetryState
    data class Finished(override val generation: Long, val result: MigrationStatus) : MigrationRetryState
}

/** The process owns in-flight retries; destroying an Activity only stops observing them. */
internal class MigrationRetryOwner(
    private val scope: CoroutineScope,
    private val query: () -> MigrationStatus,
    private val retry: suspend () -> MigrationStatus,
) {
    private val lock = Any()
    private var generation = 0L
    private val mutableState = MutableStateFlow<MigrationRetryState>(MigrationRetryState.Idle())
    val state: StateFlow<MigrationRetryState> = mutableState.asStateFlow()

    fun start() {
        // Query before taking the UI lock: open/retry may currently hold the database owner.
        val previous = query()
        synchronized(lock) {
            if (mutableState.value is MigrationRetryState.Running || previous.state != MigrationState.FAILED) return@synchronized
            // A stale click queued while a previous retry completed must not reuse its old snapshot.
            if (query() !== previous) return@synchronized
            val current = ++generation
            mutableState.value = MigrationRetryState.Running(current, previous)
            scope.launch(start = CoroutineStart.LAZY) {
                val result = try {
                    retry()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Keep the prior safe diagnostic and allow another explicit retry.
                    previous
                }
                synchronized(lock) { mutableState.value = MigrationRetryState.Finished(current, result) }
            }.start()
        }
    }
}

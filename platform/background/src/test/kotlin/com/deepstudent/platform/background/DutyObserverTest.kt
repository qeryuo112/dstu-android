package com.deepstudent.platform.background

import com.deepstudent.core.common.id.TaskId
import com.deepstudent.core.common.logging.NoopLogger
import com.deepstudent.services.runtime.api.RuntimeEvent
import com.deepstudent.services.runtime.api.RuntimeEventType
import com.deepstudent.services.runtime.api.RuntimeExecution
import com.deepstudent.services.runtime.api.RuntimeOwnership
import com.deepstudent.services.runtime.api.RuntimeSnapshot
import com.deepstudent.services.runtime.api.RuntimeState
import com.deepstudent.services.runtime.api.RuntimeTaskObserver
import com.deepstudent.services.runtime.api.RuntimeTaskQuery
import com.deepstudent.services.runtime.api.TaskCursor
import com.deepstudent.services.runtime.api.TaskKind
import com.deepstudent.services.runtime.api.TaskSubmission
import com.deepstudent.services.runtime.api.TaskTimes
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DutyObserverTest {
    @Test
    fun initialSnapshotAndLiveTerminalReleaseOnlyTarget() = runTest {
        val registry = DutyRegistry()
        val one = duty("one")
        val two = duty("two")
        registry.acquire(one)
        registry.acquire(two)
        val events = MutableSharedFlow<RuntimeEvent>()
        val states = mutableListOf<RuntimeState>()
        val observer = DutyObserver(registry, query(snapshot(one)), RuntimeTaskObserver { _, _ -> events }, ForegroundLog(NoopLogger))
        val job = launch { observer.watch(one) { states += it } }
        runCurrent()
        events.emit(event(one, RuntimeState.WAITING_APPROVAL))
        events.emit(event(one.copy(generation = 2), RuntimeState.COMPLETED))
        events.emit(event(two, RuntimeState.COMPLETED))
        assertEquals(2, registry.duties.value.size)
        events.emit(event(one, RuntimeState.COMPLETED))
        assertEquals(listOf(two), registry.duties.value)
        assertEquals(listOf(RuntimeState.RUNNING, RuntimeState.WAITING_APPROVAL), states)
        job.cancel()
    }

    @Test
    fun missingOrNewGenerationIsRetiredWithoutObserving() = runTest {
        val registry = DutyRegistry()
        val one = duty("one")
        registry.acquire(one)
        val observer = DutyObserver(registry, query(snapshot(one.copy(generation = 2))), RuntimeTaskObserver { _, _ -> error("unexpected") }, ForegroundLog(NoopLogger))
        observer.watch(one) { error("unexpected") }
        assertTrue(registry.duties.value.isEmpty())
    }

    @Test
    fun terminalSnapshotIsRetiredBeforeSubscription() = runTest {
        val registry = DutyRegistry()
        val one = duty("one")
        registry.acquire(one)
        val terminal = snapshot(one).copy(state = RuntimeState.CANCELLED)
        val observer = DutyObserver(registry, query(terminal), RuntimeTaskObserver { _, _ -> error("unexpected") }, ForegroundLog(NoopLogger))
        observer.watch(one) { error("unexpected") }
        assertTrue(registry.duties.value.isEmpty())
    }
}

internal fun snapshot(duty: ForegroundDuty): RuntimeSnapshot {
    val kind = TaskKind.parse("test.sync")
    val time = Instant.parse("2026-11-16T00:00:00Z")
    return RuntimeSnapshot(
        duty.taskId, kind, RuntimeState.RUNNING, TaskSubmission(kind),
        RuntimeOwnership(duty.generation, 1, "owner", null),
        RuntimeExecution(null, false, null, null, null), TaskTimes(time, time), TaskCursor.ZERO,
    )
}

internal fun query(value: RuntimeSnapshot?): RuntimeTaskQuery = object : RuntimeTaskQuery {
    override suspend fun snapshot(taskId: TaskId): RuntimeSnapshot? = value
    override suspend fun events(taskId: TaskId, after: TaskCursor): List<RuntimeEvent> = emptyList()
}

internal fun event(duty: ForegroundDuty, state: RuntimeState): RuntimeEvent = RuntimeEvent(
    TaskCursor.parse(1), duty.taskId, duty.generation, RuntimeEventType.STATE_CHANGED, state, Instant.EPOCH,
)

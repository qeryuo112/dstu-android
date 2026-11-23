package com.deepstudent.platform.background

import com.deepstudent.core.common.id.TaskId
import com.deepstudent.services.runtime.api.RuntimeEvent
import com.deepstudent.services.runtime.api.RuntimeSnapshot
import com.deepstudent.services.runtime.api.RuntimeState
import com.deepstudent.services.runtime.api.RuntimeTaskQuery
import com.deepstudent.services.runtime.api.TaskCursor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DutyReconcilerTest {
    @Test
    fun emptyRegistryNeverGrantsFromLedgerFactsAlone() = runBlocking {
        val registry = DutyRegistry()
        val active = snapshot(duty("task"))
        val host = TestForegroundHost(registry, query(active))
        assertTrue(DutyReconciler(registry, host.foregroundTasks.query, host).reconcile().isEmpty())
        assertTrue(registry.slots.value.isEmpty())
    }

    @Test
    fun liveReferencesRequireCurrentGenerationAndMatchingPurpose() = runBlocking {
        val registry = DutyRegistry()
        val one = duty("one")
        val terminalForTask = duty("two").copy(purpose = ForegroundPurpose.TERMINAL)
        val missing = duty("three")
        registry.acquire(one)
        registry.acquire(terminalForTask)
        registry.acquire(missing)
        val query = object : RuntimeTaskQuery {
            override suspend fun snapshot(taskId: TaskId): RuntimeSnapshot? = when (taskId.value) {
                "one" -> snapshot(one)
                "two" -> snapshot(terminalForTask)
                "three" -> null
                else -> error("unexpected task")
            }

            override suspend fun events(taskId: TaskId, after: TaskCursor): List<RuntimeEvent> = emptyList()
        }
        val host = TestForegroundHost(
            registry,
            query,
            eligible = { _, purpose -> purpose == ForegroundPurpose.DATA_SYNC },
        )
        val slots = DutyReconciler(registry, query, host).reconcile()
        assertEquals(listOf(one), registry.duties.value)
        assertEquals(1, slots.size)
    }

    @Test
    fun terminalSnapshotAndGenerationMismatchAreRetiredBeforeObservation() = runBlocking {
        val registry = DutyRegistry()
        val one = duty("one")
        val two = duty("two")
        registry.acquire(one)
        registry.acquire(two)
        val query = object : RuntimeTaskQuery {
            override suspend fun snapshot(taskId: TaskId): RuntimeSnapshot? = if (taskId == one.taskId) {
                snapshot(one).copy(state = RuntimeState.CANCELLED)
            } else {
                snapshot(two.copy(generation = 2))
            }

            override suspend fun events(taskId: TaskId, after: TaskCursor): List<RuntimeEvent> = emptyList()
        }
        val host = TestForegroundHost(registry, query)
        DutyReconciler(registry, query, host).reconcile()
        assertTrue(registry.duties.value.isEmpty())
    }

    @Test
    fun continuousEntriesAreVerifiedByLiveEligibility() = runBlocking {
        val registry = DutyRegistry()
        val live = continuousDuty("session-live")
        val closed = continuousDuty("session-closed")
        registry.acquireContinuous(live)
        registry.acquireContinuous(closed)
        val host = TestForegroundHost(
            registry,
            query(null),
            continuousEligible = { it.ownerId == "session-live" },
        )
        val slots = DutyReconciler(registry, host.foregroundTasks.query, host).reconcile()
        assertEquals(listOf(live), registry.continuousDuties.value)
        assertEquals(listOf(ForegroundSlot.Continuous(live)), slots)
    }
}

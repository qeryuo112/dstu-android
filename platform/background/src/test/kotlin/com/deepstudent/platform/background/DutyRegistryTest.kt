package com.deepstudent.platform.background

import com.deepstudent.core.common.id.TaskId
import com.deepstudent.services.runtime.api.RuntimeRecordKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DutyRegistryTest {
    @Test
    fun referencesAreIndependentAndTerminalRetiresOnlyTheMatchingGeneration() {
        val registry = DutyRegistry()
        val first = duty("one")
        val second = duty("two")
        val firstRef = registry.acquire(first)
        val firstDuplicate = registry.acquire(first)
        val secondRef = registry.acquire(second)
        assertEquals(listOf(first, second), registry.duties.value)
        firstRef.close()
        firstRef.close()
        assertEquals(listOf(first, second), registry.duties.value)
        registry.retire(first.taskId, 2)
        assertEquals(2, registry.duties.value.size)
        registry.retire(first.taskId, 1)
        assertEquals(listOf(second), registry.duties.value)
        firstDuplicate.close()
        assertFalse(registry.duties.value.isEmpty())
        secondRef.close()
        assertTrue(registry.duties.value.isEmpty())
    }

    @Test
    fun everyReferenceOwnsAUniqueProjectionKeyAndReleasesOnlyItsOwn() {
        val acquired = mutableListOf<Pair<ForegroundDuty, RuntimeRecordKey>>()
        val released = mutableListOf<Pair<ForegroundDuty, RuntimeRecordKey>>()
        val registry = DutyRegistry(object : ForegroundDutyProjector {
            override fun acquired(duty: ForegroundDuty, recordKey: RuntimeRecordKey) {
                acquired += duty to recordKey
            }

            override fun released(duty: ForegroundDuty, recordKey: RuntimeRecordKey) {
                released += duty to recordKey
            }
        })
        val one = duty("one")
        val first = registry.acquire(one)
        val duplicate = registry.acquire(one)
        assertEquals(2, acquired.size)
        assertTrue(first.recordKey.value.startsWith("fgs."))
        assertNotEquals(first.recordKey, duplicate.recordKey)
        first.close()
        assertEquals(listOf(first.recordKey), released.map { it.second })
        assertEquals(listOf(one), registry.duties.value)
        duplicate.close()
        assertEquals(2, released.size)
        assertTrue(registry.duties.value.isEmpty())
    }

    @Test
    fun retireReleasesEveryProjectionReferenceForTheGenerationOnly() {
        val released = mutableListOf<RuntimeRecordKey>()
        val registry = DutyRegistry(object : ForegroundDutyProjector {
            override fun released(duty: ForegroundDuty, recordKey: RuntimeRecordKey) {
                released += recordKey
            }
        })
        val one = duty("one")
        val other = duty("other").copy(generation = 2)
        registry.acquire(one)
        registry.acquire(one)
        registry.acquire(other)
        registry.retire(TaskId.parse("one"), 1)
        assertEquals(2, released.size)
        assertEquals(listOf(other), registry.duties.value)
    }

    @Test
    fun continuousOwnersShareTheRegistryWithoutProjectionOrTaskIds() {
        val acquired = mutableListOf<ForegroundDuty>()
        val registry = DutyRegistry(object : ForegroundDutyProjector {
            override fun acquired(duty: ForegroundDuty, recordKey: RuntimeRecordKey) {
                acquired += duty
            }
        })
        val terminal = ContinuousDuty("session-1", ForegroundPurpose.TERMINAL)
        val first = registry.acquireContinuous(terminal)
        val second = registry.acquireContinuous(terminal)
        assertTrue(acquired.isEmpty())
        assertEquals(listOf(terminal), registry.continuousDuties.value)
        assertEquals(1, registry.slots.value.size)
        assertTrue(registry.slots.value.single() is ForegroundSlot.Continuous)
        first.close()
        assertEquals(1, registry.continuousDuties.value.size)
        registry.retireContinuous("session-1")
        assertEquals(0, registry.continuousDuties.value.size)
        second.close()
        assertTrue(registry.slots.value.isEmpty())
    }

    @Test
    fun retireAllContinuousLeavesTaskDutiesUntouched() {
        val registry = DutyRegistry()
        val task = registry.acquire(duty("one"))
        registry.acquireContinuous(ContinuousDuty("session-1", ForegroundPurpose.TERMINAL))
        registry.retireAllContinuous()
        assertEquals(listOf(duty("one")), registry.duties.value)
        assertTrue(registry.continuousDuties.value.isEmpty())
        task.close()
        assertTrue(registry.slots.value.isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun sameTaskCannotHoldConflictingGenerations() {
        val registry = DutyRegistry()
        registry.acquire(duty("one"))
        registry.acquire(duty("one").copy(generation = 2))
    }

    @Test(expected = IllegalArgumentException::class)
    fun generationMustBePositive() {
        duty("one").copy(generation = 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun continuousOwnerMustBeOpaqueAndTerminal() {
        ContinuousDuty("not a valid owner", ForegroundPurpose.TERMINAL)
    }

    @Test(expected = IllegalArgumentException::class)
    fun continuousOwnerCannotBeDataSync() {
        ContinuousDuty("session-1", ForegroundPurpose.DATA_SYNC)
    }
}

internal fun duty(id: String): ForegroundDuty = ForegroundDuty(TaskId.parse(id), 1, null, ForegroundPurpose.DATA_SYNC)

internal fun continuousDuty(id: String): ContinuousDuty = ContinuousDuty(id, ForegroundPurpose.TERMINAL)

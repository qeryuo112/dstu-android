package com.deepstudent.platform.background

import com.deepstudent.core.common.id.TaskId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}

internal fun duty(id: String): ForegroundDuty =
    ForegroundDuty(TaskId.parse(id), 1, null, ForegroundPurpose.DATA_SYNC)

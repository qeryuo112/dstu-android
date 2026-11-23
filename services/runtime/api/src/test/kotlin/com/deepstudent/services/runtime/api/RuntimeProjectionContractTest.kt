package com.deepstudent.services.runtime.api

import com.deepstudent.core.common.id.TaskId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RuntimeProjectionContractTest {
    @Test
    fun taskGenerationMustBePositive() {
        val taskId = TaskId.parse("task-1")
        assertEquals(7L, RuntimeTaskGeneration(taskId, 7).generation)
        val failure = runCatching { RuntimeTaskGeneration(taskId, 0) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun recordKeyAcceptsOpaqueMachineCodesOnly() {
        assertEquals("approval.1", RuntimeRecordKey.parse("approval.1").value)
        assertEquals("duty:notes-sync-2", RuntimeRecordKey.parse("duty:notes-sync-2").value)
        for (invalid in listOf("", "-leading", "with space", "with/slash", "a".repeat(129))) {
            assertTrue(
                "must reject '$invalid'",
                runCatching { RuntimeRecordKey.parse(invalid) }.exceptionOrNull() is IllegalArgumentException,
            )
        }
    }

    @Test
    fun approvalResolutionCannotStayPending() {
        val id = RuntimeRecordKey.parse("approval")
        for (state in listOf(RuntimeApprovalState.APPROVED, RuntimeApprovalState.DENIED, RuntimeApprovalState.CANCELLED)) {
            assertEquals(state, RuntimeProjectionChange.ResolveApproval(id, state).state)
        }
        try {
            RuntimeProjectionChange.ResolveApproval(id, RuntimeApprovalState.PENDING)
            fail("expected pending resolution rejection")
        } catch (failure: IllegalArgumentException) {
            assertEquals("approval decision is required", failure.message)
        }
    }
}

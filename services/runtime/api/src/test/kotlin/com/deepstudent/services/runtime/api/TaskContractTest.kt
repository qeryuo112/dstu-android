package com.deepstudent.services.runtime.api

import com.deepstudent.core.common.error.DstuError
import com.deepstudent.core.common.error.DstuException
import com.deepstudent.core.common.error.Outcome
import com.deepstudent.core.common.error.getOrThrow
import com.deepstudent.core.common.error.suspendOutcomeCatching
import com.deepstudent.core.common.id.CorrelationId
import com.deepstudent.core.common.id.TaskId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Instant

class TaskContractTest {
    @Test
    fun taskStatusWireNamesMatchSharedDesktopContract() {
        assertEquals(
            listOf(
                "Pending",
                "Processing",
                "Streaming",
                "Paused",
                "Completed",
                "Failed",
                "Truncated",
                "Cancelled",
            ),
            TaskStatus.entries.map { it.wire },
        )
        assertTrue(TaskStatus.COMPLETED.terminal)
        assertTrue(TaskStatus.CANCELLED.terminal)
        assertEquals(TaskStatus.STREAMING, TaskStatus.fromWire("Streaming"))
    }

    @Test
    fun taskCursorUsesJsonSafeIntegerBoundary() {
        assertEquals(0L, TaskCursor.ZERO.value)
        assertEquals(9_007_199_254_740_991L, TaskCursor.parse(9_007_199_254_740_991L).value)
        val failure = runCatching { TaskCursor.parse(9_007_199_254_740_992L) }.exceptionOrNull()
        assertTrue(failure is DstuException)
        assertEquals("INTEGER_NOT_JSON_SAFE", (failure as DstuException).error.code)
    }

    @Test
    fun cancellationFailureKeepsTypedErrorAndCancellationIdentity() = runBlocking {
        val taskId = TaskId.parse("task-1")
        val failure = DstuError.unavailable("TASK_STORE", "ledger unavailable")
        val canceller = TaskCanceller { _, _ -> TaskCancelResult.Rejected(taskId, failure) }
        val rejected = canceller.cancel(taskId, "user") as TaskCancelResult.Rejected
        assertSame(failure, rejected.error)

        val cancellation = CancellationException("stop")
        try {
            suspendOutcomeCatching<Unit> { throw cancellation }
            fail("expected cancellation")
        } catch (thrown: CancellationException) {
            assertSame(cancellation, thrown)
        }
    }

    @Test
    fun explicitOutcomeCancellationIsNotConvertedToFailure() {
        val outcome = Outcome.Cancelled("stop") as Outcome<TaskHandle>
        assertTrue(outcome is Outcome.Cancelled)
        val thrown = runCatching { outcome.getOrThrow() }.exceptionOrNull()
        assertTrue(thrown is CancellationException)
    }

    @Test
    fun taskSnapshotRequiresBoundedProgress() {
        val snapshot = TaskSnapshot(
            taskId = TaskId.parse("task-2"),
            kind = TaskKind.parse("notes.save"),
            status = TaskStatus.PROCESSING,
            correlationId = CorrelationId.parse("corr-1"),
            times = TaskTimes(
                createdAt = Instant.parse("2026-11-15T08:00:00Z"),
                updatedAt = Instant.parse("2026-11-15T08:00:01Z"),
            ),
            progress = TaskProgress(percent = 50),
        )
        assertEquals(50, snapshot.progressPercent)
        try {
            TaskProgress(percent = 101)
            fail("expected progress rejection")
        } catch (failure: IllegalArgumentException) {
            assertEquals("task progress must be within 0..100", failure.message)
        }
    }

    @Test
    fun portsAreNarrowAndFakesNeedOnlyTheirOwnSurface() = runBlocking {
        val taskId = TaskId.parse("task-3")
        val submitter = TaskSubmitter { TaskHandle(taskId, deduplicated = false) }
        val query = object : TaskQuery {
            override suspend fun snapshot(taskId: TaskId): TaskSnapshot? = null
            override suspend fun events(taskId: TaskId, after: TaskCursor?): List<TaskEvent> = emptyList()
        }
        val observer = TaskObserver { _, _ -> emptyFlow<TaskEvent>() }
        assertEquals(taskId, submitter.submit(TaskSubmission(TaskKind.parse("engine.warm"))).taskId)
        assertEquals(null, query.snapshot(taskId))
        assertNotNull(observer.observe(taskId, null))

        val failure = runCatching {
            throw DstuException(DstuError.validation("BAD", "bad"))
        }.exceptionOrNull()
        assertTrue(failure is DstuException)
    }
}

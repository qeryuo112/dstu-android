package com.deepstudent.platform.background

import com.deepstudent.core.common.id.TaskId
import com.deepstudent.core.common.logging.Logger
import com.deepstudent.core.common.logging.NoopLogger
import com.deepstudent.core.common.logging.LogRecord
import com.deepstudent.services.runtime.api.RuntimeCancelResult
import com.deepstudent.services.runtime.api.RuntimeSnapshot
import com.deepstudent.services.runtime.api.RuntimeTaskCatalog
import com.deepstudent.services.runtime.api.RuntimeTaskCanceller
import com.deepstudent.services.runtime.api.RuntimeTaskObserver
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimeoutTasksTest {
    @Test
    fun checkpointFinishesBeforeCancelAndDutiesReleaseIndependently() = runBlocking {
        val one = duty("one")
        val two = duty("two")
        val checkpointStarted = CompletableDeferred<Unit>()
        val finishCheckpoint = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<TaskId>()
        val host = testHost(one, checkpointStarted, finishCheckpoint, cancelled)
        host.foregroundDuties.acquire(one)
        host.foregroundDuties.acquire(two)
        try {
            TimeoutTasks.request(host, listOf(one))
            withTimeout(3_000) { checkpointStarted.await() }
            assertFalse(cancelled.isCompleted)
            assertEquals(listOf(two), host.foregroundDuties.duties.value)
            finishCheckpoint.complete(Unit)
            assertEquals(one.taskId, withTimeout(3_000) { cancelled.await() })
        } finally {
            host.foregroundScope.cancel()
        }
    }

    @Test
    fun checkpointFailureStillRequestsCancellation() = runBlocking {
        val one = duty("one")
        val cancelled = CompletableDeferred<TaskId>()
        val host = testHost(one, CompletableDeferred(), CompletableDeferred(), cancelled, fail = true)
        host.foregroundDuties.acquire(one)
        try {
            TimeoutTasks.request(host, listOf(one))
            assertEquals(one.taskId, withTimeout(3_000) { cancelled.await() })
            assertTrue(host.foregroundDuties.duties.value.isEmpty())
        } finally {
            host.foregroundScope.cancel()
        }
    }

    @Test
    fun loggerRecordsOnlyFixedFieldsAndNeverCopiesTaskPayload() {
        val records = mutableListOf<LogRecord>()
        val logger = object : Logger {
            override fun log(record: LogRecord) { records += record }
        }
        ForegroundLog(logger).event("background.timeout", duty("one"), "checkpoint_failed", 5)
        assertEquals(setOf("owner", "state", "elapsedMs"), records.single().fields.keys().toSet())
        assertFalse(records.single().toString().contains("sensitive checkpoint failure"))
        val failingLogger = object : Logger {
            override fun log(record: LogRecord) { throw IllegalStateException("sensitive logger failure") }
        }
        ForegroundLog(failingLogger).event("background.timeout", duty("one"), "checkpoint_failed")
    }

    private fun testHost(
        duty: ForegroundDuty,
        started: CompletableDeferred<Unit>,
        finish: CompletableDeferred<Unit>,
        cancelled: CompletableDeferred<TaskId>,
        fail: Boolean = false,
    ): RuntimeForegroundHost = object : RuntimeForegroundHost {
        override val foregroundDuties = DutyRegistry()
        override val foregroundScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        override val foregroundLogger: Logger = NoopLogger
        override fun isDataSyncForegroundTask(snapshot: RuntimeSnapshot): Boolean = true
        override val foregroundTasks = ForegroundTaskPorts(
            query(snapshot(duty)),
            object : RuntimeTaskCatalog {
                override suspend fun activeSnapshots(afterTaskId: String?, limit: Int): List<RuntimeSnapshot> = emptyList()
            },
            RuntimeTaskObserver { _, _ -> emptyFlow() },
            object : RuntimeTaskCanceller {
                override suspend fun cancel(taskId: TaskId): RuntimeCancelResult = RuntimeCancelResult.StaleGeneration(taskId)
                override suspend fun cancelIfGeneration(taskId: TaskId, generation: Long): RuntimeCancelResult {
                    assertEquals(duty.generation, generation)
                    cancelled.complete(taskId)
                    return RuntimeCancelResult.Requested(taskId)
                }
            },
        )

        override suspend fun checkpointForegroundTask(duty: ForegroundDuty) {
            if (fail) error("sensitive checkpoint failure")
            started.complete(Unit)
            finish.await()
        }
    }
}

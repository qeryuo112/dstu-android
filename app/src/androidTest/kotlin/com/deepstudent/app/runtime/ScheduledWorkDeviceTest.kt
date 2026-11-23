package com.deepstudent.app.runtime

import android.content.Intent
import android.os.Process
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import com.deepstudent.app.MainActivity
import com.deepstudent.app.scheduling.submission
import com.deepstudent.core.common.id.TaskId
import com.deepstudent.platform.background.scheduling.ScheduleId
import com.deepstudent.services.runtime.api.RuntimeEventType
import com.deepstudent.services.runtime.api.RuntimeSnapshot
import com.deepstudent.services.runtime.api.RuntimeState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ScheduledWorkDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val f get() = ScheduledDeviceFixture()

    @Test(timeout = 90_000)
    fun uiCompetesWithRealWorkerThenSystemTimeoutYieldsTheSameCheckpoint() = runBlocking {
        val fixture = f
        val row = fixture.createNote("system-timeout")
        val activity = ActivityScenario.launch<MainActivity>(Intent(fixture.app, MainActivity::class.java))
        try {
            fixture.gate.writeText("hold")
            val job = fixture.scheduledJob(row)
            assertTrue(fixture.issue("run -f", job).contains("Running job"))
            awaitRuntime { fixture.task(row)?.execution?.checkpoint != null }
            val previous = requireNotNull(fixture.task(row))
            val runtime = fixture.app.runtime().controller
            assertFalse(fixture.hasFact(previous))
            awaitTag("workspace-open-schedules")
            compose.onNodeWithTag("workspace-open-schedules").performClick()
            val run = "schedule-run-${row.id.value}"
            awaitTag(run)
            compose.onNodeWithTag(run).performClick()
            awaitRuntime { fixture.task(row)?.ownership?.ownerId == previous.ownership.ownerId }
            assertEquals(previous.ownership.ownerEpoch, fixture.task(row)?.ownership?.ownerEpoch)
            assertTrue(fixture.issue("timeout", job).contains("job", ignoreCase = true))
            awaitRuntime { fixture.task(row)?.ownership?.ownerId == null }
            val yielded = requireNotNull(fixture.task(row))
            assertEquals(previous.execution.checkpoint, yielded.execution.checkpoint)
            assertFalse(yielded.execution.cancelRequested)
            assertEquals(RuntimeState.RUNNING, yielded.state)
            assertFalse(fixture.hasFact(yielded))
            check(fixture.gate.delete())
            // A forced JobScheduler run still respects WorkManager's own retry time.
            fixture.evidence("S225_SYSTEM_TIMEOUT_RELEASED waiting_for_retry=true")
            kotlinx.coroutines.delay(31_000)
            val next = fixture.scheduledJob(row)
            assertTrue(fixture.issue("run -f", next).contains("Running job"))
            assertCompleted(fixture, previous, "system-timeout")
            val repeated = runtime.runScheduled(submission(row, row.occurrence))
            assertEquals(previous.taskId, repeated.taskId)
            assertFalse(repeated.executedHere)
            fixture.evidence("S225_SYSTEM_STOP same_task=true checkpoint=true cancelled=false business_facts=1")
        } finally {
            fixture.gate.delete()
            fixture.schedules.cancel(row.id)
            activity.close()
        }
    }

    @Test(timeout = 60_000)
    fun seedWorkForExternalForceStop() = runBlocking {
        val fixture = f
        check(!fixture.marker.exists())
        fixture.gate.writeText("hold")
        val row = fixture.createNote("force-stop")
        val job = fixture.scheduledJob(row)
        assertTrue(fixture.issue("run -f", job).contains("Running job"))
        awaitRuntime { fixture.task(row)?.execution?.checkpoint != null }
        val snapshot = requireNotNull(fixture.task(row))
        assertFalse(fixture.hasFact(snapshot))
        fixture.marker.writeText("${Process.myPid()}\n${row.id.value}\n${snapshot.taskId.value}\n${snapshot.ownership.ownerEpoch}\n")
        fixture.evidence("S225_FORCE_STOP_SEED pid=${Process.myPid()} job=${job.id}")
    }

    @Test(timeout = 75_000)
    fun visibleReopenRequeuesTheOldTaskWithoutDuplicatingBusiness() = runBlocking {
        val fixture = f
        val lines = fixture.marker.readLines()
        assertNotEquals(lines[0].toInt(), Process.myPid())
        val id = ScheduleId.parse(lines[1])
        val taskId = TaskId.parse(lines[2])
        val runtime = fixture.app.runtime().controller
        val old = requireNotNull(runtime.snapshot(taskId))
        assertTrue(old.ownership.ownerEpoch > lines[3].toLong())
        assertEquals(null, old.ownership.ownerId)
        assertFalse(old.state.terminal)
        assertTrue(old.execution.checkpoint != null)
        assertFalse(fixture.hasFact(old))
        val row = LocalScheduleFixtureLookup(fixture).enabled(id)
        val activity = ActivityScenario.launch<MainActivity>(Intent(fixture.app, MainActivity::class.java))
        try {
            check(fixture.gate.delete())
            val job = fixture.scheduledJob(row)
            if (fixture.schedules.work.snapshot(row.id).any { it.runAttemptCount > 0 }) kotlinx.coroutines.delay(31_000)
            assertTrue(fixture.issue("run -f", job).contains("Running job"))
            assertCompleted(fixture, old, "force-stop")
            check(fixture.marker.delete())
            fixture.evidence("S225_FORCE_STOP_REOPEN old=${lines[0]} pid=${Process.myPid()} same_task=true business_facts=1")
        } finally {
            activity.close()
        }
    }

    private suspend fun assertCompleted(fixture: ScheduledDeviceFixture, previous: RuntimeSnapshot, body: String) {
        val controller = fixture.app.runtime().controller
        awaitRuntime { controller.snapshot(previous.taskId)?.state == RuntimeState.COMPLETED }
        val final = requireNotNull(controller.snapshot(previous.taskId))
        assertEquals(previous.ownership.generation, final.ownership.generation)
        assertTrue(final.ownership.ownerEpoch > previous.ownership.ownerEpoch)
        assertTrue(fixture.hasFact(final))
        val notes = fixture.app.storage().notes.list().filter { it.title == fixture.title && it.content == body }
        assertEquals(1, notes.size)
        assertEquals(notes.single().id, final.execution.receipt)
        val events = controller.events(previous.taskId)
        assertEquals(1, events.count { it.type == RuntimeEventType.CHECKPOINT })
        assertEquals(1, events.count { it.type == RuntimeEventType.RECONCILED })
        assertEquals(0, events.count { it.type == RuntimeEventType.CANCEL_REQUESTED })
    }

    private fun awaitTag(tag: String) = compose.waitUntil(15_000) {
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
    }
}

private class LocalScheduleFixtureLookup(private val fixture: ScheduledDeviceFixture) {
    suspend fun enabled(id: ScheduleId): com.deepstudent.app.scheduling.LocalSchedule =
        requireNotNull(com.deepstudent.app.scheduling.LocalScheduleStore(fixture.app).find(id)).also { check(it.enabled) }
}

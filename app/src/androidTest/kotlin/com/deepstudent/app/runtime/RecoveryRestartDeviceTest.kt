package com.deepstudent.app.runtime

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.deepstudent.app.DstuApplication
import com.deepstudent.core.common.id.TaskId
import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.features.notes.api.CreateNote
import com.deepstudent.features.notes.data.NoteTaskIdentity
import com.deepstudent.services.runtime.api.RuntimeState
import com.deepstudent.services.runtime.api.TaskSubmission
import com.deepstudent.services.runtime.backend.LedgerCommand
import com.deepstudent.services.runtime.backend.LedgerResult
import com.deepstudent.services.runtime.backend.leaseToken
import java.io.File
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecoveryRestartDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as DstuApplication
    private val marker = File(app.cacheDir, "s1-10-recovery-fixture")

    @Test fun writeCrashWindowFixtures(): Unit = runBlocking {
        selected("writeCrashWindowFixtures")
        withContext(Dispatchers.IO) {
            val runtime = app.runtime()
            val storage = app.storage()
            val prefix = "recovery_${System.nanoTime()}"
            val completed = TaskId.parse("${prefix}_done")
            val partial = TaskId.parse("${prefix}_partial")
            createRunning(runtime, completed)
            val fact = storage.noteTasks.create(CreateNote("Crash window fixture", "completed before projection"),
                NoteTaskIdentity(completed.value, 1))
            val token = createRunning(runtime, partial)
            runtime.ledger.apply(partial, LedgerCommand.Checkpoint(token, JsonValue.text("partial text retained 🧪")), Instant.now())
            marker.writeText("${completed.value}\n${partial.value}\n${fact.noteId}\n${android.os.Process.myPid()}")
        }
    }

    @Test fun startupReconcilesCommittedFactAndPreservesInterruptedOutput(): Unit = runBlocking {
        selected("startupReconcilesCommittedFactAndPreservesInterruptedOutput")
        withContext(Dispatchers.IO) {
            val values = marker.readLines()
            assertNotEquals(values[3].toInt(), android.os.Process.myPid())
            val runtime = app.runtime()
            val done = TaskId.parse(values[0])
            val partial = TaskId.parse(values[1])
            val completed = checkNotNull(runtime.controller.snapshot(done))
            assertEquals(RuntimeState.COMPLETED, completed.state)
            assertEquals(values[2], completed.execution.receipt)
            val interrupted = checkNotNull(runtime.controller.snapshot(partial))
            assertEquals(RuntimeState.INTERRUPTED, interrupted.state)
            assertEquals(JsonValue.text("partial text retained 🧪"), interrupted.execution.checkpoint)
            val count = runtime.controller.events(done).size
            runtime.startup.recover()
            assertEquals(count, runtime.controller.events(done).size)
            assertEquals("completed before projection", app.storage().notes.get(values[2])?.content)
            app.storage().notes.delete(values[2])
            marker.delete()
        }
    }

    private suspend fun createRunning(runtime: MainRuntimeResources, id: TaskId): com.deepstudent.services.runtime.backend.LeaseToken {
        val now = Instant.now()
        runtime.ledger.create(id, TaskSubmission(MainRuntimeResources.NOTE_CREATE), now)
        val claim = runtime.ledger.apply(id, LedgerCommand.Claim(runtime.ownerId, now.plusSeconds(600)), now) as LedgerResult.Applied
        val token = checkNotNull(claim.snapshot.leaseToken())
        runtime.ledger.apply(id, LedgerCommand.Transition(token, RuntimeState.RUNNING), now)
        return token
    }

    private fun selected(method: String) {
        assumeTrue("Explicit host restart sequence required", InstrumentationRegistry.getArguments()
            .getString("class") == "${javaClass.name}#$method")
    }
}

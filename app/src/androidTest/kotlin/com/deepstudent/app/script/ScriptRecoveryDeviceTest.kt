package com.deepstudent.app.script

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.deepstudent.app.runtime.MainRuntimeResources
import com.deepstudent.app.runtime.script.MainScriptResources
import com.deepstudent.core.common.id.TaskId
import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.services.runtime.api.BusinessCompletionConsumers
import com.deepstudent.services.runtime.api.RuntimeState
import com.deepstudent.services.runtime.api.TaskSubmission
import com.deepstudent.services.runtime.backend.LedgerCommand
import com.deepstudent.services.runtime.backend.LedgerResult
import com.deepstudent.services.runtime.backend.leaseToken
import com.deepstudent.services.script.backend.ScriptReceipt
import com.deepstudent.services.script.backend.ScriptReceiptContent
import com.deepstudent.services.script.backend.ScriptTerminalState
import com.deepstudent.services.script.backend.receiptFactId
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScriptRecoveryDeviceTest {
    @Test
    fun startupRecoversCommittedScriptFactAndInterruptsUnfinishedTask(): Unit = runBlocking {
        val fixture = ScriptRuntimeFixture()
        try {
            var runtime = fixture.open()
            val completedId = TaskId.parse("s1_19_${System.nanoTime()}_fact")
            val interruptedId = TaskId.parse("s1_19_${System.nanoTime()}_interrupted")
            createRunning(runtime, completedId)
            createRunning(runtime, interruptedId, checkpoint = PARTIAL)

            val fact = ScriptReceipt(
                factId = receiptFactId(completedId, 1),
                taskId = completedId,
                generation = 1,
                state = ScriptTerminalState.COMPLETED,
                receipt = "ptc:completed:0:recovered",
                committedAt = Instant.now(),
                content = ScriptReceiptContent(result = JsonValue.obj("recovered" to JsonValue.Bool(true)),
                trace = emptyList(),
                failure = null,
                durationMillis = 0,
                callsUsed = 0),
            )
            runtime.script.receipts.commit(fact)

            fixture.closeRuntime()
            runtime = fixture.open()
            val report = runtime.startup.recover()
            assertEquals(2, report.claimed)
            assertEquals(1, report.reconciled)
            assertEquals(1, report.interrupted)
            assertEquals(0, report.resumed)
            assertEquals(0, report.confirmationRequired)
            assertEquals(0, report.failures)

            val completed = checkNotNull(runtime.controller.snapshot(completedId))
            val interrupted = checkNotNull(runtime.controller.snapshot(interruptedId))
            assertEquals(RuntimeState.COMPLETED, completed.state)
            assertEquals(fact.factId, completed.execution.businessFactId)
            assertEquals(fact.receipt, completed.execution.receipt)
            assertEquals(RuntimeState.INTERRUPTED, interrupted.state)
            assertNull(interrupted.execution.businessFactId)
            assertEquals(PARTIAL, interrupted.execution.checkpoint)
            assertEquals(fact, runtime.script.receipts.findReceipt(completedId, 1))
            assertTrue(runtime.script.receipts.pending(BusinessCompletionConsumers.RUNTIME, 10).isEmpty())
            assertEquals(1, terminalEventCount(runtime, completedId))
            assertEquals(1, terminalEventCount(runtime, interruptedId))

            val again = runtime.startup.recover()
            assertEquals(0, again.claimed)
            assertEquals(1, terminalEventCount(runtime, completedId))
            assertEquals(1, terminalEventCount(runtime, interruptedId))
        } finally {
            fixture.close()
        }
    }

    private suspend fun createRunning(
        runtime: MainRuntimeResources,
        taskId: TaskId,
        checkpoint: JsonValue? = JsonValue.obj("stage" to JsonValue.text("running")),
    ) {
        val now = Instant.now()
        val created = runtime.ledger.create(
            taskId,
            TaskSubmission(MainScriptResources.KIND, JsonValue.obj("script" to JsonValue.text("40+2"))),
            now,
        )
        assertTrue(created.inserted)
        val claim = runtime.ledger.apply(
            taskId,
            LedgerCommand.Claim(runtime.ownerId, now.plusSeconds(600)),
            now,
        ) as LedgerResult.Applied
        val token = checkNotNull(claim.snapshot.leaseToken())
        val running = runtime.ledger.apply(
            taskId,
            LedgerCommand.Transition(token, RuntimeState.RUNNING),
            now,
        ) as LedgerResult.Applied
        assertEquals(RuntimeState.RUNNING, running.snapshot.state)
        assertTrue(
            runtime.ledger.apply(taskId, LedgerCommand.Checkpoint(token, checkpoint), now) is LedgerResult.Applied,
        )
    }

    private companion object {
        val PARTIAL = JsonValue.obj(
            "stage" to JsonValue.text("running"),
            "partial" to JsonValue.text("not resumable"),
        )
    }
}

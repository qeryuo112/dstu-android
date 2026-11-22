package com.deepstudent.app.script

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.deepstudent.app.runtime.MainRuntimeResources
import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.services.runtime.api.RuntimeCancelResult
import com.deepstudent.services.runtime.api.RuntimeState
import com.deepstudent.services.script.api.ScriptFailureKind
import com.deepstudent.services.script.backend.ScriptTerminalState
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScriptRuntimeDeviceTest {
    @Test
    fun successfulScriptCallsTwoToolsAndSurvivesReopen(): Unit = runBlocking {
        val fixture = ScriptRuntimeFixture()
        try {
            var runtime = fixture.open()
            assertIsolatedDatabases(fixture)
            val handle = runtime.controller.submit(scriptSubmission(TWO_TOOLS))
            val completed = awaitTerminal(runtime, handle.taskId)
            assertEquals(RuntimeState.COMPLETED, completed.state)

            val receipt = checkNotNull(
                runtime.script.receipts.findReceipt(handle.taskId, completed.ownership.generation),
            )
            assertEquals(ScriptTerminalState.COMPLETED, receipt.state)
            assertEquals(2, receipt.callsUsed)
            assertEquals(
                listOf("builtin-script_echo", "builtin-script_add"),
                receipt.trace.map { it.tool },
            )
            assertTrue(receipt.trace.all { it.ok })
            assertEquals("hello", textField(receipt.result, "echo"))
            assertEquals(42L, numberField(receipt.result, "sum"))
            assertEquals(receipt.factId, completed.execution.businessFactId)
            assertEquals(receipt.receipt, completed.execution.receipt)
            assertEquals(1, terminalEventCount(runtime, handle.taskId))

            fixture.closeRuntime()
            runtime = fixture.open()
            val reopened = checkNotNull(runtime.controller.snapshot(handle.taskId))
            assertEquals(RuntimeState.COMPLETED, reopened.state)
            assertEquals(receipt.factId, reopened.execution.businessFactId)
            assertEquals(receipt.receipt, reopened.execution.receipt)
            assertEquals(
                receipt,
                runtime.script.receipts.findReceipt(handle.taskId, reopened.ownership.generation),
            )
            assertEquals(1, terminalEventCount(runtime, handle.taskId))
        } finally {
            fixture.close()
        }
    }

    @Test
    fun policyViolationHasSingleTerminalFactAndEvent(): Unit = runBlocking {
        val fixture = ScriptRuntimeFixture()
        try {
            val runtime = fixture.open()
            val handle = runtime.controller.submit(scriptSubmission(POLICY_VIOLATION))
            val failed = awaitTerminal(runtime, handle.taskId)
            assertEquals(RuntimeState.FAILED, failed.state)

            val receipt = checkNotNull(
                runtime.script.receipts.findReceipt(handle.taskId, failed.ownership.generation),
            )
            assertEquals(ScriptTerminalState.FAILED, receipt.state)
            assertEquals(ScriptFailureKind.POLICY_VIOLATION, receipt.failure?.kind)
            assertEquals(0, receipt.callsUsed)
            assertTrue(receipt.trace.isEmpty())
            assertEquals(receipt.factId, failed.execution.businessFactId)
            assertEquals(1, terminalEventCount(runtime, handle.taskId))
        } finally {
            fixture.close()
        }
    }

    @Test
    fun toolBudgetOverrunHasSingleTerminalFactAndEvent(): Unit = runBlocking {
        val fixture = ScriptRuntimeFixture()
        try {
            val runtime = fixture.open()
            val handle = runtime.controller.submit(scriptSubmission(OVER_BUDGET, maxCalls = 1))
            val failed = awaitTerminal(runtime, handle.taskId)
            assertEquals(RuntimeState.FAILED, failed.state)

            val receipt = checkNotNull(
                runtime.script.receipts.findReceipt(handle.taskId, failed.ownership.generation),
            )
            assertEquals(ScriptTerminalState.FAILED, receipt.state)
            assertEquals(ScriptFailureKind.TOOL_CALL_LIMIT, receipt.failure?.kind)
            assertEquals(1, receipt.callsUsed)
            assertEquals(1, receipt.trace.size)
            assertEquals(receipt.factId, failed.execution.businessFactId)
            assertEquals(1, terminalEventCount(runtime, handle.taskId))
        } finally {
            fixture.close()
        }
    }

    @Test
    fun cancelKeepsReceiptAndDoesNotAffectAnotherTask(): Unit = runBlocking {
        val fixture = ScriptRuntimeFixture()
        try {
            var runtime = fixture.open()
            val cancelledTask = runtime.controller.submit(scriptSubmission(CANCELLABLE, maxCalls = 5))
            awaitRunningCheckpoint(runtime, cancelledTask.taskId)
            val sibling = runtime.controller.submit(scriptSubmission(TWO_TOOLS))
            assertTrue(runtime.controller.cancel(cancelledTask.taskId) is RuntimeCancelResult.Requested)

            val cancelled = awaitTerminal(runtime, cancelledTask.taskId)
            val completed = awaitTerminal(runtime, sibling.taskId)
            assertEquals(RuntimeState.CANCELLED, cancelled.state)
            assertEquals(RuntimeState.COMPLETED, completed.state)
            assertNotNull(cancelled.execution.businessFactId)

            val cancelledReceipt = checkNotNull(
                runtime.script.receipts.findReceipt(cancelledTask.taskId, cancelled.ownership.generation),
            )
            val completedReceipt = checkNotNull(
                runtime.script.receipts.findReceipt(sibling.taskId, completed.ownership.generation),
            )
            assertEquals(ScriptTerminalState.CANCELLED, cancelledReceipt.state)
            assertEquals(cancelledReceipt.factId, cancelled.execution.businessFactId)
            assertEquals(ScriptTerminalState.COMPLETED, completedReceipt.state)
            assertEquals(completedReceipt.factId, completed.execution.businessFactId)
            assertEquals(1, terminalEventCount(runtime, cancelledTask.taskId))
            assertEquals(1, terminalEventCount(runtime, sibling.taskId))

            fixture.closeRuntime()
            runtime = fixture.open()
            assertEquals(RuntimeState.CANCELLED,
                checkNotNull(runtime.controller.snapshot(cancelledTask.taskId)).state)
            assertEquals(RuntimeState.COMPLETED,
                checkNotNull(runtime.controller.snapshot(sibling.taskId)).state)
            assertEquals(
                cancelledReceipt,
                runtime.script.receipts.findReceipt(cancelledTask.taskId, cancelled.ownership.generation),
            )
            assertEquals(
                completedReceipt,
                runtime.script.receipts.findReceipt(sibling.taskId, completed.ownership.generation),
            )
        } finally {
            fixture.close()
        }
    }

    private fun assertIsolatedDatabases(fixture: ScriptRuntimeFixture) {
        assertTrue(File(fixture.root, "active/databases/runtime.db").isFile)
        assertTrue(File(fixture.root, "active/databases/script.db").isFile)
        assertTrue(fixture.root.canonicalPath.startsWith(fixture.app.cacheDir.canonicalPath))
    }

    private fun textField(value: JsonValue?, key: String): String =
        (((value as JsonValue.Object)[key]) as JsonValue.Text).value

    private fun numberField(value: JsonValue?, key: String): Long =
        (((value as JsonValue.Object)[key]) as JsonValue.Number).asLongOrNull()!!

    private companion object {
        const val TWO_TOOLS = """
            const echoed = call("script_echo", {value:"hello"});
            const added = call("script_add", {a:40,b:2});
            ({echo:echoed.output.value,sum:added.output.sum});
        """
        const val POLICY_VIOLATION = """call("ptc_run", {}); "unreachable";"""
        const val OVER_BUDGET = """
            call("script_echo", {value:"first"});
            call("script_echo", {value:"second"});
        """
        const val CANCELLABLE = """
            call("script_echo", {value:"started"});
            while (true) {}
        """
    }
}

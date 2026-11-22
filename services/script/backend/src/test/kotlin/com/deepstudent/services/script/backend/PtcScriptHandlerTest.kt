package com.deepstudent.services.script.backend

import com.deepstudent.core.common.id.TaskId
import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.services.script.api.ScriptEngine
import com.deepstudent.services.script.api.ScriptExecutionRequest
import com.deepstudent.services.script.api.ScriptExecutionResult
import com.deepstudent.services.script.api.ScriptExecutionStats
import com.deepstudent.services.script.api.ScriptFailureKind
import com.deepstudent.services.script.api.ScriptToolPort
import com.deepstudent.services.script.api.ScriptToolResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PtcScriptHandlerTest {
    @Test
    fun twoRealToolsCompleteAndCommitBusinessFact() = runBlocking {
        val fixture = Fixture()
        val receipt = fixture.handler.execute(fixture.context, payload("two"))
        assertEquals(ScriptTerminalState.COMPLETED, receipt.state)
        assertEquals(2, receipt.callsUsed)
        assertEquals(2, receipt.trace.size)
        assertEquals(listOf("builtin-script_echo", "builtin-script_add"), fixture.toolCalls)
        assertEquals(2, fixture.authorizations)
        assertEquals(JsonValue.integer(42), (receipt.result as JsonValue.Object)["sum"])
        assertEquals(receipt, fixture.store.findReceipt(fixture.context.taskId, 1))
        assertEquals(listOf(receipt.completion()), fixture.store.pending("runtime", 10))
    }

    @Test
    fun unknownToolReturnsErrorWithoutConsumingBudgetOrAuthorization() = runBlocking {
        val fixture = Fixture()
        val receipt = fixture.handler.execute(fixture.context, payload("unknown-tool"))
        assertEquals(ScriptTerminalState.COMPLETED, receipt.state)
        assertEquals("unknown tool", (receipt.result as JsonValue.Text).value)
        assertEquals(0, receipt.callsUsed)
        assertEquals(0, fixture.toolCalls.size)
        assertEquals(0, fixture.authorizations)
        assertTrue(receipt.trace.isEmpty())
    }

    @Test
    fun everyAllowedCallNeedsItsOwnAuthorization() = runBlocking {
        val fixture = Fixture(authorizer = {
            if (it.tool == "builtin-script_add") {
                ScriptAuthorization.Denied("USER_DENIED", "denied now")
            } else {
                ScriptAuthorization.Allowed
            }
        })
        val receipt = fixture.handler.execute(fixture.context, payload("two"))
        assertEquals(ScriptTerminalState.FAILED, receipt.state)
        assertEquals(1, fixture.toolCalls.size)
        assertEquals(2, fixture.authorizations)
        assertEquals(2, receipt.callsUsed)
        assertFalse(receipt.trace.last().ok)
    }

    @Test
    fun toolCallBudgetTerminatesAfterTheAllowedCall() = runBlocking {
        val fixture = Fixture()
        val receipt = fixture.handler.execute(fixture.context, payload("two", maxCalls = 1))
        assertEquals(ScriptTerminalState.FAILED, receipt.state)
        assertEquals(ScriptFailureKind.TOOL_CALL_LIMIT, receipt.failure?.kind)
        assertEquals(1, fixture.toolCalls.size)
        assertEquals(1, receipt.callsUsed)
        assertEquals(1, receipt.trace.size)
    }

    @Test
    fun cancellationCommitsCancelledFactAndPropagatesCancellation() = runBlocking {
        val fixture = Fixture(mode = EngineMode.CANCEL)
        val job = launch {
            fixture.handler.execute(fixture.context, payload("cancel"))
        }
        withTimeout(5_000) { fixture.engine.started.await() }
        job.cancelAndJoin()
        assertEquals(ScriptTerminalState.CANCELLED, fixture.store.findReceipt(fixture.context.taskId, 1)?.state)
    }

    private fun payload(script: String, maxCalls: Int = 50): JsonValue = JsonValue.obj(
        "script" to JsonValue.text(script),
        "maxCalls" to JsonValue.integer(maxCalls.toLong()),
    )

    private enum class EngineMode { NORMAL, CANCEL }

    private class Fixture(
        mode: EngineMode = EngineMode.NORMAL,
        authorizer: ScriptToolAuthorizer = ScriptToolAuthorizer { ScriptAuthorization.Allowed },
    ) {
        val context = FakeContext()
        val store = MemoryReceiptStore()
        val toolCalls = mutableListOf<String>()
        var authorizations = 0
        val engine = FakeEngine(mode)
        private val tools = ScriptToolPort { tool, arguments ->
            toolCalls += tool
            when (tool) {
                "builtin-script_echo" -> ScriptToolResult.Success(arguments)
                "builtin-script_add" -> ScriptToolResult.Success(
                    JsonValue.obj("sum" to JsonValue.integer(42)),
                )
                else -> ScriptToolResult.Failure("UNKNOWN_TOOL", "unknown tool")
            }
        }
        private val countingAuthorizer = ScriptToolAuthorizer {
            authorizations += 1
            authorizer.authorize(it)
        }
        private val policy = ScriptToolPolicy(
            setOf("script_echo", "builtin-script_add"),
            countingAuthorizer,
        )
        val handler = PtcScriptHandler(engine, policy, tools, store)
    }

    private class FakeEngine(private val mode: EngineMode) : ScriptEngine {
        val started = CompletableDeferred<Unit>()

        override fun execute(request: ScriptExecutionRequest): ScriptExecutionResult {
            return when (mode) {
                EngineMode.CANCEL -> {
                    started.complete(Unit)
                    while (!request.cancellation.isCancelled()) Thread.sleep(1)
                    failure(ScriptFailureKind.CANCELLED, "cancelled")
                }
                EngineMode.NORMAL -> runNormal(request)
            }
        }

        private fun runNormal(request: ScriptExecutionRequest): ScriptExecutionResult {
            if (request.source == "unknown-tool") {
                val unknown = request.tools.call("unknown", JsonValue.Object.EMPTY)
                val message = (unknown as ScriptToolResult.Failure).message
                return ScriptExecutionResult.Success(
                    JsonValue.text(message),
                    ScriptExecutionStats(durationMillis = 1),
                )
            }
            val echo = request.tools.call("script_echo", JsonValue.obj("value" to JsonValue.text("hello")))
            if (echo is ScriptToolResult.Fatal) return fatal(echo)
            val add = request.tools.call("builtin-script_add", JsonValue.obj("a" to JsonValue.integer(2)))
            if (add is ScriptToolResult.Fatal) return fatal(add)
            return ScriptExecutionResult.Success(
                JsonValue.obj(
                    "echo" to ((echo as ScriptToolResult.Success).value as JsonValue.Object)["value"]!!,
                    "sum" to ((add as ScriptToolResult.Success).value as JsonValue.Object)["sum"]!!,
                ),
                ScriptExecutionStats(durationMillis = 5),
            )
        }

        private fun fatal(result: ScriptToolResult): ScriptExecutionResult =
            ScriptExecutionResult.Failure(
                (result as ScriptToolResult.Fatal).kind,
                result.message,
                ScriptExecutionStats(durationMillis = 5),
            )

        private fun failure(kind: ScriptFailureKind, message: String): ScriptExecutionResult =
            ScriptExecutionResult.Failure(kind, message, ScriptExecutionStats())
    }

    private class FakeContext : PtcTaskContext {
        override val taskId: TaskId = TaskId.parse("script-task")
        override val generation: Long = 1
        val checkpoints = mutableListOf<JsonValue?>()

        override suspend fun checkpoint(value: JsonValue?) {
            checkpoints += value
        }
    }

    private class MemoryReceiptStore : ScriptReceiptStore {
        private val receipts = linkedMapOf<String, ScriptReceipt>()
        private val acknowledged = mutableSetOf<String>()

        override suspend fun findReceipt(taskId: TaskId, generation: Long): ScriptReceipt? =
            receipts.values.firstOrNull { it.taskId == taskId && it.generation == generation }

        override suspend fun commit(receipt: ScriptReceipt) {
            val existing = receipts[receipt.factId]
            if (existing != null) {
                check(existing == receipt) { "script receipt conflict" }
                return
            }
            receipts[receipt.factId] = receipt
        }

        override suspend fun pending(consumerId: String, limit: Int): List<ScriptCompletion> =
            receipts.values.filter { it.factId !in acknowledged }
                .sortedBy { it.committedAt }.take(limit).map { it.completion() }

        override suspend fun acknowledge(consumerId: String, factId: String) {
            acknowledged += factId
        }
    }
}

package com.deepstudent.services.script.backend

import com.deepstudent.core.common.id.TaskId
import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.core.common.time.MonotonicClock
import com.deepstudent.services.script.api.ScriptFailureKind
import com.deepstudent.services.script.api.ScriptToolPort
import com.deepstudent.services.script.api.ScriptToolResult
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptToolBrokerTest {
    @Test
    fun authorizerExceptionIsSanitizedAndToolNeverRuns() {
        val secret = "private-authorizer-detail"
        val calls = mutableListOf<String>()
        val broker = broker(
            authorizer = { throw IllegalStateException(secret) },
            tools = { calls += it; ScriptToolResult.Success(JsonValue.Null) },
        )
        val result = broker.call("script_echo", JsonValue.Object.EMPTY) as ScriptToolResult.Fatal
        assertEquals(ScriptFailureKind.POLICY_VIOLATION, result.kind)
        assertTrue(!result.message.contains(secret))
        assertTrue(calls.isEmpty())
        assertTrue(broker.trace.single().error == "AUTHORIZATION_FAILED")
        assertTrue(!broker.trace.toString().contains(secret))
    }

    @Test
    fun deadlineStopsBeforeAndAfterSynchronousToolCall() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var now = 0L
        val clock = MonotonicClock { now }
        val broker = ScriptToolBroker(
            TaskId.parse("budget"), 1, 10, policy(),
            ScriptToolPort { _, _ ->
                entered.countDown()
                release.await(5, TimeUnit.SECONDS)
                ScriptToolResult.Success(JsonValue.text("late"))
            },
            clock,
            control = ScriptCallControl(100) { false },
        )
        val worker = Thread { broker.call("script_echo", JsonValue.Object.EMPTY) }
        worker.start()
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        now = 100
        val result = runCatching { broker.call("script_add", JsonValue.Object.EMPTY) }
        release.countDown()
        worker.join(2_000)
        val failure = result.getOrNull() as ScriptToolResult.Fatal
        assertEquals(ScriptFailureKind.WALL_TIME, failure.kind)
        assertEquals(1, broker.callsUsed)
    }

    @Test
    fun unknownToolDoesNotConsumeBudgetOrAuthorize() {
        var authorizations = 0
        var toolRuns = 0
        val broker = broker(
            authorizer = { authorizations += 1; ScriptAuthorization.Allowed },
            tools = { toolRuns += 1; ScriptToolResult.Success(JsonValue.Null) },
        )
        val result = broker.call("missing", JsonValue.Object.EMPTY)
        assertEquals(ScriptToolResult.Failure("UNKNOWN_TOOL", "unknown tool"), result)
        assertEquals(0, broker.callsUsed)
        assertEquals(0, authorizations)
        assertEquals(0, toolRuns)
        assertTrue(broker.trace.isEmpty())
    }

    private fun broker(
        authorizer: ScriptToolAuthorizer,
        tools: (String) -> ScriptToolResult,
    ): ScriptToolBroker = ScriptToolBroker(
        TaskId.parse("broker"), 1, 10,
        ScriptToolPolicy(setOf("script_echo", "script_add"), authorizer),
        ScriptToolPort { tool, _ -> tools(tool) },
        MonotonicClock { 0 },
    )

    private fun policy(): ScriptToolPolicy =
        ScriptToolPolicy(setOf("script_echo", "script_add"), ScriptToolAuthorizer {
            ScriptAuthorization.Allowed
        })
}

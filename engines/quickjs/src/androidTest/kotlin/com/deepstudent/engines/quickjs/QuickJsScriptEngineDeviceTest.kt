package com.deepstudent.engines.quickjs

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.services.script.api.ScriptCancellation
import com.deepstudent.services.script.api.ScriptEngine
import com.deepstudent.services.script.api.ScriptExecutionRequest
import com.deepstudent.services.script.api.ScriptExecutionResult
import com.deepstudent.services.script.api.ScriptFailureKind
import com.deepstudent.services.script.api.ScriptLimits
import com.deepstudent.services.script.api.ScriptToolPort
import com.deepstudent.services.script.api.ScriptToolResult
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QuickJsScriptEngineDeviceTest {
    private val engine: ScriptEngine = QuickJsScriptEngine()

    @Test fun pinnedEngineAndUnicodeCrossJni() {
        assertEquals("2026-06-04", QuickJsScriptEngine.version())
        val result = run(
            """const echoed = call("script_echo", JSON.stringify({value:"汉😀"})); ({text: echoed.output.value});""",
        )
        assertEquals("汉😀", text(objectField(result, "text")))
    }

    @Test fun lastExpressionAndTwoRealTools() {
        val two = run(asset("ptc-two-tools.js"))
        assertEquals("hello", text(objectField(two, "echo")))
        assertEquals(42L, number(objectField(two, "sum")))

        val last = run("({answer: 40 + 2})")
        assertEquals(42L, number(objectField(last, "answer")))
    }

    @Test fun toolFailureIsReturnedButPolicyFatalIsUncatchable() {
        val failure = run(asset("ptc-tool-failure.js"))
        assertEquals("failed", text(failure))

        val denied = runFailure(asset("ptc-deny.js"))
        assertEquals(ScriptFailureKind.POLICY_VIOLATION, denied.kind)
    }

    @Test fun unknownToolDoesNotConsumeEngineBudget() {
        val result = run("""call("unknown", {}).error""")
        assertEquals("unknown tool", text(result))
    }

    @Test fun toolCallBudgetTerminatesOnSecondCall() {
        val failure = runFailure(asset("ptc-over-budget.js"), ScriptLimits(maxToolCalls = 1))
        assertEquals(ScriptFailureKind.TOOL_CALL_LIMIT, failure.kind)
        assertEquals(1, failure.stats.callsUsed)
    }

    @Test fun wallMemoryStackResultAndSourceBudgetsAreReal() {
        val wall = runFailure("while (true) {}", ScriptLimits(
            wallTime = Duration.ofMillis(1),
            maxEngineTicks = ScriptLimits.MAX_ENGINE_TICKS,
        ))
        assertEquals(ScriptFailureKind.WALL_TIME, wall.kind)

        val memory = runFailure(
            "new Uint8Array(16 * 1024 * 1024)",
            ScriptLimits(maxMemoryBytes = 8L * 1024 * 1024),
        )
        assertEquals(ScriptFailureKind.MEMORY_LIMIT, memory.kind)

        val stack = runFailure(
            "function f(){ return f(); } f()",
            ScriptLimits(maxStackBytes = ScriptLimits.MIN_STACK_BYTES),
        )
        assertEquals(ScriptFailureKind.STACK_LIMIT, stack.kind)

        val result = runFailure("\"x\".repeat(1000)", ScriptLimits(maxResultBytes = 100))
        assertEquals(ScriptFailureKind.RESULT_LIMIT, result.kind)

        val source = runFailure("\"source is too long\"", ScriptLimits(maxSourceBytes = 4))
        assertEquals(ScriptFailureKind.SOURCE_LIMIT, source.kind)
    }

    @Test fun externalCancellationInterruptsNativeLoop() {
        val cancelled = AtomicBoolean(false)
        val canceller = Thread {
            Thread.sleep(10)
            cancelled.set(true)
        }.apply { start() }
        val failure = runFailure(
            "while (true) {}",
            ScriptLimits(maxEngineTicks = ScriptLimits.MAX_ENGINE_TICKS),
            ScriptCancellation { cancelled.get() },
        )
        canceller.join()
        assertEquals(ScriptFailureKind.CANCELLED, failure.kind)
    }

    private fun run(
        source: String,
        limits: ScriptLimits = ScriptLimits(),
        cancellation: ScriptCancellation = ScriptCancellation { false },
    ): JsonValue {
        val result = engine.execute(ScriptExecutionRequest(source, limits, tools(), cancellation))
        if (result is ScriptExecutionResult.Failure) {
            throw AssertionError("expected success, got ${result.kind}: ${result.message}")
        }
        return (result as ScriptExecutionResult.Success).value
    }

    private fun runFailure(
        source: String,
        limits: ScriptLimits = ScriptLimits(),
        cancellation: ScriptCancellation = ScriptCancellation { false },
    ): ScriptExecutionResult.Failure {
        val result = engine.execute(ScriptExecutionRequest(source, limits, tools(), cancellation))
        assertTrue("expected failure, got $result", result is ScriptExecutionResult.Failure)
        return result as ScriptExecutionResult.Failure
    }

    private fun tools(): ScriptToolPort = ScriptToolPort { tool, arguments ->
        when (tool.removePrefix("builtin-")) {
            "script_echo" -> ScriptToolResult.Success(
                JsonValue.obj(
                    "value" to ((arguments as JsonValue.Object)["value"] ?: JsonValue.Null),
                ),
            )
            "script_add" -> ScriptToolResult.Success(
                JsonValue.obj("sum" to JsonValue.integer(42)),
            )
            "script_fail" -> ScriptToolResult.Failure("TOOL_FAILED", "failed")
            "script_deny" -> ScriptToolResult.Fatal(
                ScriptFailureKind.POLICY_VIOLATION,
                "denied",
            )
            else -> ScriptToolResult.Failure("UNKNOWN_TOOL", "unknown tool")
        }
    }

    private fun asset(name: String): String =
        InstrumentationRegistry.getInstrumentation().context.assets.open(name)
            .bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun objectField(value: JsonValue, key: String): JsonValue =
        (value as JsonValue.Object)[key] ?: error("missing $key")

    private fun text(value: JsonValue): String = (value as JsonValue.Text).value
    private fun number(value: JsonValue): Long = (value as JsonValue.Number).asLongOrNull()!!
}

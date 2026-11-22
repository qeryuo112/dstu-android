package com.deepstudent.services.script.backend

import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.services.script.api.ScriptLimits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptTaskRequestTest {
    @Test
    fun maxCallsDefaultsAndHardLimitsAreAppliedPerValue() {
        assertEquals(ScriptLimits.DEFAULT_MAX_TOOL_CALLS, parseCalls(null))
        assertEquals(ScriptLimits.DEFAULT_MAX_TOOL_CALLS, parseCalls(0))
        assertEquals(ScriptLimits.DEFAULT_MAX_TOOL_CALLS, parseCalls(-1))
        assertEquals(1, parseCalls(1))
        assertEquals(ScriptLimits.MAX_TOOL_CALLS, parseCalls(ScriptLimits.MAX_TOOL_CALLS))
        assertEquals(ScriptLimits.MAX_TOOL_CALLS, parseCalls(ScriptLimits.MAX_TOOL_CALLS + 1))
    }

    @Test
    fun wallAndMemoryBudgetsAreClampedToThePinnedEnvelope() {
        val default = ScriptTaskRequest.parse(JsonValue.obj("script" to JsonValue.text("1+1")))
        assertEquals(ScriptLimits.DEFAULT_WALL_TIME, default.limits.wallTime)
        assertEquals(ScriptLimits.DEFAULT_MAX_MEMORY_BYTES, default.limits.maxMemoryBytes)
        val short = ScriptTaskRequest.parse(
            JsonValue.obj(
                "script" to JsonValue.text("1+1"),
                "wallTimeMillis" to JsonValue.integer(10),
                "maxMemoryBytes" to JsonValue.integer(1),
            ),
        )
        assertEquals(10, short.limits.wallTime.toMillis())
        assertEquals(ScriptLimits.MIN_MEMORY_BYTES, short.limits.maxMemoryBytes)
    }

    @Test
    fun invalidAndOversizeSourcesFailClosed() {
        val missing = runCatching {
            ScriptTaskRequest.parse(JsonValue.obj("maxCalls" to JsonValue.integer(1)))
        }.exceptionOrNull()
        assertTrue(missing is ScriptRequestException)
        val oversized = runCatching {
            ScriptTaskRequest.parse(
                JsonValue.obj("script" to JsonValue.text("x".repeat(ScriptLimits.DEFAULT_MAX_SOURCE_BYTES + 1))),
            )
        }.exceptionOrNull()
        assertTrue(oversized is ScriptRequestException)
    }

    private fun parseCalls(value: Int?): Int {
        val fields = mutableListOf<Pair<String, JsonValue>>("script" to JsonValue.text("1+1"))
        if (value != null) fields += "maxCalls" to JsonValue.integer(value.toLong())
        return ScriptTaskRequest.parse(JsonValue.obj(*fields.toTypedArray())).limits.maxToolCalls
    }
}

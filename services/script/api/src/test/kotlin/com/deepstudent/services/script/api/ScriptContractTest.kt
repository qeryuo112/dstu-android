package com.deepstudent.services.script.api

import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Test

class ScriptContractTest {
    @Test
    fun defaultsMatchTheFrozenPtcSurface() {
        val limits = ScriptLimits()
        assertEquals(64 * 1024, limits.maxSourceBytes)
        assertEquals(50, limits.maxToolCalls)
        assertEquals(Duration.ofSeconds(120), limits.wallTime)
        assertEquals(32L * 1024 * 1024, limits.maxMemoryBytes)
        assertEquals(512L * 1024, limits.maxStackBytes)
        assertEquals(1_000, limits.maxEngineTicks)
        assertEquals(100_000, ScriptLimits.MAX_ENGINE_TICKS)
        assertEquals(256 * 1024, limits.maxResultBytes)
    }

    @Test
    fun callerBudgetsAreClampedWithoutRaisingHardLimits() {
        assertEquals(ScriptLimits.MAX_TOOL_CALLS, ScriptLimits.clampToolCalls(9_999))
        assertEquals(ScriptLimits.DEFAULT_MAX_TOOL_CALLS, ScriptLimits.clampToolCalls(0))
        assertEquals(Duration.ofSeconds(1), ScriptLimits.clampWallTime(1_000))
        assertEquals(ScriptLimits.MAX_WALL_TIME, ScriptLimits.clampWallTime(999_999))
    }

    @Test
    fun policyFatalAndToolFailureAreDistinct() {
        val denied = ScriptToolResult.Fatal(ScriptFailureKind.POLICY_VIOLATION, "denied")
        val failed = ScriptToolResult.Failure("TOOL", "failed")
        assertEquals(ScriptFailureKind.POLICY_VIOLATION, denied.kind)
        assertEquals("TOOL", failed.code)
    }
}

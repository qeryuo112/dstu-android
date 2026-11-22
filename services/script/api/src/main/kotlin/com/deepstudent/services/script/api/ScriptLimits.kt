package com.deepstudent.services.script.api

import java.time.Duration

/**
 * Hard S1 PTC limits. Callers may lower these values but cannot raise them.
 *
 * [maxEngineTicks] counts QuickJS interrupt polls. The pinned engine polls
 * every 10,000 VM instructions, so this is an engine-native operation budget
 * in addition to the wall-clock and tool-call budgets.
 */
data class ScriptLimits(
    val maxSourceBytes: Int = DEFAULT_MAX_SOURCE_BYTES,
    val maxToolCalls: Int = DEFAULT_MAX_TOOL_CALLS,
    val wallTime: Duration = DEFAULT_WALL_TIME,
    val maxMemoryBytes: Long = DEFAULT_MAX_MEMORY_BYTES,
    val maxStackBytes: Long = DEFAULT_MAX_STACK_BYTES,
    val maxEngineTicks: Int = DEFAULT_MAX_ENGINE_TICKS,
    val maxResultBytes: Int = DEFAULT_MAX_RESULT_BYTES,
) {
    init {
        require(maxSourceBytes in 1..DEFAULT_MAX_SOURCE_BYTES) { "invalid source budget" }
        require(maxToolCalls in 1..MAX_TOOL_CALLS) { "invalid tool-call budget" }
        require(!wallTime.isZero && !wallTime.isNegative && wallTime <= MAX_WALL_TIME) {
            "invalid wall budget"
        }
        require(maxMemoryBytes in MIN_MEMORY_BYTES..DEFAULT_MAX_MEMORY_BYTES) {
            "invalid memory budget"
        }
        require(maxStackBytes in MIN_STACK_BYTES..DEFAULT_MAX_STACK_BYTES) { "invalid stack budget" }
        require(maxEngineTicks in 1..MAX_ENGINE_TICKS) { "invalid engine-tick budget" }
        require(maxResultBytes in 1..DEFAULT_MAX_RESULT_BYTES) { "invalid result budget" }
    }

    companion object {
        const val DEFAULT_MAX_SOURCE_BYTES = 64 * 1024
        const val DEFAULT_MAX_TOOL_CALLS = 50
        const val MAX_TOOL_CALLS = 200
        const val DEFAULT_MAX_MEMORY_BYTES = 32L * 1024 * 1024
        const val MIN_MEMORY_BYTES = 1024L * 1024
        const val DEFAULT_MAX_STACK_BYTES = 512L * 1024
        const val MIN_STACK_BYTES = 64L * 1024
        const val DEFAULT_MAX_ENGINE_TICKS = 1_000
        const val MAX_ENGINE_TICKS = 100_000
        const val DEFAULT_MAX_RESULT_BYTES = 256 * 1024
        val DEFAULT_WALL_TIME: Duration = Duration.ofSeconds(120)
        val MAX_WALL_TIME: Duration = Duration.ofSeconds(600)

        fun clampToolCalls(requested: Long?): Int =
            requested?.takeIf { it > 0 }?.coerceAtMost(MAX_TOOL_CALLS.toLong())?.toInt()
                ?: DEFAULT_MAX_TOOL_CALLS

        fun clampWallTime(requestedMillis: Long?): Duration {
            val value = requestedMillis?.takeIf { it > 0 } ?: DEFAULT_WALL_TIME.toMillis()
            return Duration.ofMillis(value.coerceAtMost(MAX_WALL_TIME.toMillis()))
        }
    }
}

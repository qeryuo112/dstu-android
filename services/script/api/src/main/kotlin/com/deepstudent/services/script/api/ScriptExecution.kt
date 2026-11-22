package com.deepstudent.services.script.api

import com.deepstudent.core.common.json.JsonValue

enum class ScriptFailureKind {
    INVALID_REQUEST,
    SCRIPT,
    SOURCE_LIMIT,
    WALL_TIME,
    INSTRUCTION_LIMIT,
    MEMORY_LIMIT,
    STACK_LIMIT,
    TOOL_CALL_LIMIT,
    POLICY_VIOLATION,
    RESULT_LIMIT,
    ENGINE,
    INTERNAL,
    CANCELLED,
}

data class ScriptExecutionStats(
    val callsUsed: Int = 0,
    val durationMillis: Long = 0,
    val engineTicks: Long = 0,
    val currentMemoryBytes: Long = 0,
)

sealed interface ScriptExecutionResult {
    val stats: ScriptExecutionStats

    data class Success(
        val value: JsonValue,
        override val stats: ScriptExecutionStats,
    ) : ScriptExecutionResult

    data class Failure(
        val kind: ScriptFailureKind,
        val message: String,
        override val stats: ScriptExecutionStats,
    ) : ScriptExecutionResult
}

fun interface ScriptCancellation {
    fun isCancelled(): Boolean
}

sealed interface ScriptToolResult {
    data class Success(val value: JsonValue) : ScriptToolResult

    /** A tool-level failure is returned to the script as {ok:false,error}. */
    data class Failure(val code: String, val message: String) : ScriptToolResult

    /** A policy or hard-budget violation cannot be caught by the script. */
    data class Fatal(
        val kind: ScriptFailureKind,
        val message: String,
    ) : ScriptToolResult
}

fun interface ScriptToolPort {
    fun call(tool: String, arguments: JsonValue): ScriptToolResult
}

/**
 * One isolated script execution. Implementations must enforce every supplied
 * engine budget and must stop cooperatively when [ScriptExecutionRequest.cancellation]
 * requests it.
 */
interface ScriptEngine {
    fun execute(request: ScriptExecutionRequest): ScriptExecutionResult
}

data class ScriptExecutionRequest(
    val source: String,
    val limits: ScriptLimits,
    val tools: ScriptToolPort,
    val cancellation: ScriptCancellation,
)

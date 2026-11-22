package com.deepstudent.services.script.backend

import com.deepstudent.core.common.id.Digests
import com.deepstudent.core.common.id.TaskId
import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.core.common.time.ClockChecks
import com.deepstudent.core.common.time.MonotonicClock
import com.deepstudent.services.script.api.ScriptFailureKind
import com.deepstudent.services.script.api.ScriptCancellation
import com.deepstudent.services.script.api.ScriptToolPort
import com.deepstudent.services.script.api.ScriptToolResult
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.RejectedExecutionException

data class ScriptTraceEntry(
    val sequence: Int,
    val tool: String,
    val argumentsHash: String,
    val durationMillis: Long,
    val resultBytes: Int,
    val ok: Boolean,
    val error: String? = null,
)

data class ScriptToolAuthorizationRequest(
    val taskId: TaskId,
    val generation: Long,
    val tool: String,
    val arguments: JsonValue,
)

sealed interface ScriptAuthorization {
    data object Allowed : ScriptAuthorization
    data class Denied(val code: String, val message: String) : ScriptAuthorization
}

fun interface ScriptToolAuthorizer {
    fun authorize(request: ScriptToolAuthorizationRequest): ScriptAuthorization

    companion object {
        val DENY_ALL: ScriptToolAuthorizer =
            ScriptToolAuthorizer { ScriptAuthorization.Denied("DENIED", "tool authorization denied") }
    }
}

/**
 * The allowlist and the authorizer are both mandatory on every call. Repeating
 * the same tool does not reuse a previous decision.
 */
class ScriptToolPolicy(
    allowedTools: Set<String>,
    val authorizer: ScriptToolAuthorizer,
) {
    val canonicalTools: Set<String> = allowedTools.mapNotNull(::canonicalToolName).toSet()

    init {
        require(canonicalTools.isNotEmpty()) { "the PTC allowlist must not be empty" }
    }

    fun allows(tool: String): Boolean = tool in canonicalTools
}

data class ScriptCallControl(
    val deadlineNanos: Long = Long.MAX_VALUE,
    val cancellation: ScriptCancellation = ScriptCancellation { false },
)

class ScriptToolBroker(
    private val taskId: TaskId,
    private val generation: Long,
    private val maxCalls: Int,
    private val policy: ScriptToolPolicy,
    private val tools: ScriptToolPort,
    private val monotonicClock: MonotonicClock,
    private val control: ScriptCallControl = ScriptCallControl(),
) : ScriptToolPort {
    private val entries = mutableListOf<ScriptTraceEntry>()
    private val openCalls = mutableMapOf<Int, Future<*>>()
    private val closed = AtomicBoolean(false)
    private var calls = 0
    private val cancellation get() = control.cancellation
    private val deadlineNanos get() = control.deadlineNanos

    val callsUsed: Int get() = calls
    val trace: List<ScriptTraceEntry> get() = entries.toList()

    override fun call(tool: String, arguments: JsonValue): ScriptToolResult {
        if (closed.get()) {
            return ScriptToolResult.Fatal(ScriptFailureKind.INTERNAL, "script broker is closed")
        }
        val canonical = canonicalToolName(tool)
            ?: return denied(ScriptFailureKind.POLICY_VIOLATION, "invalid tool name")
        if (canonical == PTC_RUN_TOOL) {
            return denied(ScriptFailureKind.POLICY_VIOLATION, "ptc_run cannot invoke itself")
        }
        if (!policy.allows(canonical)) {
            return ScriptToolResult.Failure("UNKNOWN_TOOL", "unknown tool")
        }
        callViolation()?.let { return it }
        val sequence = calls++
        val started = monotonicClock.nowNanos()
        return authorizedCall(sequence, canonical, arguments, started)
    }

    private fun authorizedCall(sequence: Int, canonical: String, arguments: JsonValue, started: Long): ScriptToolResult {
        val authorization = try {
            policy.authorizer.authorize(
                ScriptToolAuthorizationRequest(taskId, generation, canonical, arguments),
            )
        } catch (_: Throwable) {
            record(sequence, canonical, arguments, started, 0, false, "AUTHORIZATION_FAILED")
            return ScriptToolResult.Fatal(
                ScriptFailureKind.POLICY_VIOLATION,
                "tool authorization failed",
            )
        }
        if (authorization is ScriptAuthorization.Denied) {
            val code = sanitizeCode(authorization.code)
            record(sequence, canonical, arguments, started, 0, false, code)
            return ScriptToolResult.Fatal(ScriptFailureKind.POLICY_VIOLATION, "tool authorization denied")
        }
        deadlineViolation()?.let {
            record(sequence, canonical, arguments, started, 0, false, it.message)
            return it
        }
        if (cancellation.isCancelled()) {
            record(sequence, canonical, arguments, started, 0, false, "CANCELLED")
            return ScriptToolResult.Fatal(ScriptFailureKind.CANCELLED, "script cancelled")
        }
        val result = invokeWithinDeadline(sequence, canonical, arguments, started)
        return recordResult(sequence, canonical, arguments, started, result)
    }

    private fun callViolation(): ScriptToolResult.Fatal? = when {
        calls >= maxCalls -> denied(ScriptFailureKind.TOOL_CALL_LIMIT, "tool-call budget exhausted")
        cancellation.isCancelled() -> denied(ScriptFailureKind.CANCELLED, "script cancelled")
        else -> deadlineViolation()
    }

    fun close() {
        if (closed.compareAndSet(false, true)) {
            synchronized(openCalls) {
                openCalls.values.forEach { it.cancel(true) }
                openCalls.clear()
            }
        }
    }

    private fun invokeWithinDeadline(
        sequence: Int,
        tool: String,
        arguments: JsonValue,
        started: Long,
    ): ScriptToolResult {
        val future = try { TOOL_EXECUTOR.submit<ScriptToolResult> { tools.call(tool, arguments) } }
        catch (_: RejectedExecutionException) { return ScriptToolResult.Fatal(ScriptFailureKind.INTERNAL, "tool execution capacity exhausted") }
        synchronized(openCalls) { openCalls[sequence] = future }
        return try {
            awaitCall(future)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            future.cancel(true)
            ScriptToolResult.Fatal(ScriptFailureKind.CANCELLED, "script cancelled")
        } finally {
            synchronized(openCalls) { openCalls.remove(sequence) }
        }
    }

    private fun awaitCall(future: Future<ScriptToolResult>): ScriptToolResult {
        while (true) {
            executionViolation()?.let {
                future.cancel(true)
                return it
            }
            try {
                val result = future.get(POLL_MILLIS, TimeUnit.MILLISECONDS)
                executionViolation()?.let { return it }
                return result
            } catch (_: TimeoutException) {
                continue
            } catch (_: ExecutionException) {
                return ScriptToolResult.Failure("TOOL_EXECUTION_FAILED", "tool execution failed")
            } catch (_: InterruptedException) {
                throw InterruptedException()
            }
        }
    }

    private fun executionViolation(): ScriptToolResult.Fatal? =
        if (closed.get() || cancellation.isCancelled()) denied(ScriptFailureKind.CANCELLED, "script cancelled")
        else deadlineViolation()

    private fun deadlineViolation(): ScriptToolResult.Fatal? {
        if (deadlineNanos != Long.MAX_VALUE && monotonicClock.nowNanos() >= deadlineNanos) {
            return ScriptToolResult.Fatal(ScriptFailureKind.WALL_TIME, "script wall-time budget exhausted")
        }
        return null
    }

    private fun recordResult(
        sequence: Int,
        tool: String,
        arguments: JsonValue,
        started: Long,
        result: ScriptToolResult,
    ): ScriptToolResult {
        when (result) {
            is ScriptToolResult.Success -> record(
                sequence, tool, arguments, started, result.value.utf8Size(), true, null,
            )
            is ScriptToolResult.Failure -> record(
                sequence, tool, arguments, started, 0, false, "${result.code}: ${result.message}",
            )
            is ScriptToolResult.Fatal -> record(
                sequence, tool, arguments, started, 0, false, result.message,
            )
        }
        return result
    }

    private fun record(
        sequence: Int,
        tool: String,
        arguments: JsonValue,
        started: Long,
        bytes: Int,
        ok: Boolean,
        error: String?,
    ) {
        entries += ScriptTraceEntry(
            sequence,
            tool,
            "sha256:" + Digests.sha256HexUtf8(arguments.canonical()).take(16),
            ClockChecks.monotonicElapsedMillis(started, monotonicClock.nowNanos()) ?: 0,
            bytes,
            ok,
            error?.let(::truncateError),
        )
    }

    private fun denied(kind: ScriptFailureKind, message: String): ScriptToolResult.Fatal =
        ScriptToolResult.Fatal(kind, message)

    private fun truncateError(value: String): String =
        if (value.length <= MAX_ERROR_CHARS) value else value.take(MAX_ERROR_CHARS) + "..."

    private fun sanitizeCode(value: String): String =
        value.takeIf { SAFE_CODE.matches(it) } ?: "DENIED"

    private companion object {
        const val PTC_RUN_TOOL = "builtin-ptc_run"
        const val MAX_ERROR_CHARS = 300
        const val POLL_MILLIS = 10L
        val SAFE_CODE = Regex("[A-Z0-9_]{1,64}")
        val TOOL_EXECUTOR: ExecutorService = ThreadPoolExecutor(0, 4, 60, TimeUnit.SECONDS, SynchronousQueue(),
            { runnable -> Thread(runnable, "dstu-script-tool").apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())
    }
}

fun canonicalToolName(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty() || trimmed.length > 128) return null
    return if (trimmed.startsWith("builtin-")) trimmed else "builtin-$trimmed"
}

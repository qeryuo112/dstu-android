package com.deepstudent.engines.quickjs

import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.services.script.api.ScriptEngine
import com.deepstudent.services.script.api.ScriptExecutionRequest
import com.deepstudent.services.script.api.ScriptExecutionResult
import com.deepstudent.services.script.api.ScriptExecutionStats
import com.deepstudent.services.script.api.ScriptFailureKind
import com.deepstudent.services.script.api.ScriptToolResult
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Real QuickJS execution. Source and host JSON cross JNI as UTF-8; the native
 * core owns all engine budgets and the interrupt flag.
 */
class QuickJsScriptEngine : ScriptEngine {
    companion object {
        fun version(): String = QuickJsNative.nativeVersion()
    }

    override fun execute(request: ScriptExecutionRequest): ScriptExecutionResult {
        val sourceBytes = request.source.toByteArray(Charsets.UTF_8).size
        if (sourceBytes > request.limits.maxSourceBytes) {
            return failure(ScriptFailureKind.SOURCE_LIMIT, "script source exceeds the source budget")
        }
        if (request.cancellation.isCancelled()) {
            return failure(ScriptFailureKind.CANCELLED, "script cancelled")
        }
        val bridge = HostBridge(request)
        val handle = try {
            QuickJsNative.nativeCreate(
                bridge,
                request.limits.maxToolCalls,
                request.limits.wallTime.toMillis().coerceAtLeast(1),
                request.limits.maxMemoryBytes,
                request.limits.maxStackBytes,
                request.limits.maxEngineTicks,
                request.limits.maxResultBytes,
            )
        } catch (_: Throwable) {
            0L
        }
        if (handle == 0L) return failure(ScriptFailureKind.ENGINE, "QuickJS runtime unavailable")
        val running = AtomicBoolean(true)
        val watchdog = watchdog(handle, running, request)
        return try {
            val payload = QuickJsNative.nativeExecute(handle, request.source)
            checkedResult(request, parse(payload))
        } catch (_: Throwable) {
            failure(ScriptFailureKind.ENGINE, "QuickJS execution failed")
        } finally {
            running.set(false)
            watchdog.interrupt()
            try {
                watchdog.join()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            QuickJsNative.nativeDestroy(handle)
        }
    }

    private fun watchdog(
        handle: Long,
        running: AtomicBoolean,
        request: ScriptExecutionRequest,
    ): Thread = Thread({
        while (running.get()) {
            if (request.cancellation.isCancelled()) {
                QuickJsNative.nativeInterrupt(handle)
                return@Thread
            }
            try {
                Thread.sleep(2)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return@Thread
            }
        }
    }, "dstu-quickjs-watchdog").apply {
        isDaemon = true
        start()
    }

    private fun checkedResult(request: ScriptExecutionRequest, result: ScriptExecutionResult): ScriptExecutionResult =
        if (request.cancellation.isCancelled() && result is ScriptExecutionResult.Success) {
            failure(ScriptFailureKind.CANCELLED, "script cancelled")
        } else result

    private fun parse(payload: String): ScriptExecutionResult {
        val root = JsonValue.parseObject(payload, "QuickJS result")
        val stats = ScriptExecutionStats(
            number(root["callsUsed"]).toInt(),
            number(root["durationMillis"]),
            number(root["engineTicks"]),
            number(root["currentMemoryBytes"]),
        )
        if (text(root["status"]) == "ok") {
            val valueText = text(root["valueJson"])
            return ScriptExecutionResult.Success(JsonValue.parse(valueText), stats)
        }
        val kind = runCatching {
            ScriptFailureKind.valueOf(text(root["failureKind"]))
        }.getOrDefault(ScriptFailureKind.ENGINE)
        return ScriptExecutionResult.Failure(kind, text(root["message"]), stats)
    }

    private fun failure(kind: ScriptFailureKind, message: String): ScriptExecutionResult =
        ScriptExecutionResult.Failure(kind, message, ScriptExecutionStats())

    private fun text(value: JsonValue?): String =
        (value as? JsonValue.Text)?.value ?: error("invalid QuickJS field")

    private fun number(value: JsonValue?): Long =
        (value as? JsonValue.Number)?.asLongOrNull() ?: error("invalid QuickJS number")

    private class HostBridge(
        private val request: ScriptExecutionRequest,
    ) : QuickJsHostBridge {
        override fun onCall(tool: String, argsJson: String): String {
            val arguments = try {
                JsonValue.parse(argsJson)
            } catch (_: Throwable) {
                return "FTOOL_ARGUMENTS\ninvalid tool arguments"
            }
            return when (val result = request.tools.call(tool, arguments)) {
                is ScriptToolResult.Success -> "S" + result.value.canonical()
                is ScriptToolResult.Failure -> "F" + safeCode(result.code) + "\n" + safe(result.message)
                is ScriptToolResult.Fatal -> "X" + result.kind.name + "\n" + safe(result.message)
            }
        }

        private fun safe(value: String): String =
            value.take(300).replace('\n', ' ')

        private fun safeCode(value: String): String =
            value.takeIf { SAFE_CODE.matches(it) } ?: "TOOL_FAILURE"

        private companion object {
            val SAFE_CODE = Regex("[A-Z0-9_]{1,64}")
        }
    }
}

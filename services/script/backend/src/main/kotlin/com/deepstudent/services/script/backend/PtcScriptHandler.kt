package com.deepstudent.services.script.backend

import com.deepstudent.core.common.id.Digests
import com.deepstudent.core.common.id.TaskId
import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.core.common.logging.LogLevel
import com.deepstudent.core.common.logging.LogRecord
import com.deepstudent.core.common.logging.Logger
import com.deepstudent.core.common.logging.NoopLogger
import com.deepstudent.core.common.time.SystemMonotonicClock
import com.deepstudent.core.common.time.SystemWallClock
import com.deepstudent.core.common.time.WallClock
import com.deepstudent.core.common.time.MonotonicClock
import com.deepstudent.services.script.api.ScriptCancellation
import com.deepstudent.services.script.api.ScriptEngine
import com.deepstudent.services.script.api.ScriptExecutionRequest
import com.deepstudent.services.script.api.ScriptExecutionResult
import com.deepstudent.services.script.api.ScriptExecutionStats
import com.deepstudent.services.script.api.ScriptFailureKind
import com.deepstudent.services.script.api.ScriptToolPort
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext

interface PtcTaskContext {
    val taskId: TaskId
    val generation: Long
    suspend fun checkpoint(value: JsonValue?)
}

/** Owns PTC policy, cancellation and the terminal business fact, not the runtime ledger. */
class PtcScriptHandler(
    private val engine: ScriptEngine,
    private val policy: ScriptToolPolicy,
    private val tools: ScriptToolPort,
    private val receipts: ScriptReceiptStore,
    private val logger: Logger = NoopLogger,
    private val wallClock: WallClock = SystemWallClock,
    private val monotonicClock: MonotonicClock = SystemMonotonicClock,
) {
    suspend fun execute(context: PtcTaskContext, payload: JsonValue): ScriptReceipt {
        val started = wallClock.now()
        val request = try {
            ScriptTaskRequest.parse(payload)
        } catch (error: ScriptRequestException) {
            return finishWithoutExecution(context, started, error)
        }
        val job = currentCoroutineContext()[Job]
        val cancellation = ScriptCancellation { job?.isActive == false }
        val deadline = deadlineNanos(request.limits.wallTime.toNanos())
        val broker = ScriptToolBroker(
            context.taskId, context.generation, request.limits.maxToolCalls,
            policy, tools, monotonicClock, ScriptCallControl(deadline, cancellation),
        )
        val result = try {
            context.checkpoint(JsonValue.obj("stage" to JsonValue.text("running")))
            executeEngine(request, broker, cancellation)
        } catch (_: CancellationException) {
            cancelledStats(broker)
        } catch (_: Throwable) {
            ScriptExecutionResult.Failure(
                ScriptFailureKind.INTERNAL,
                "script task failed",
                ScriptExecutionStats(callsUsed = broker.callsUsed),
            )
        } finally {
            broker.close()
        }
        val receipt = receipt(context, started, broker, result, job)
        withContext(NonCancellable) {
            receipts.commit(receipt)
            runCatching {
                context.checkpoint(
                    JsonValue.obj(
                        "stage" to JsonValue.text(receipt.state.name.lowercase()),
                        "callsUsed" to JsonValue.integer(receipt.callsUsed.toLong()),
                    ),
                )
            }
        }
        log(context, receipt, started)
        if (receipt.state == ScriptTerminalState.CANCELLED) {
            throw CancellationException("script task cancelled")
        }
        return receipt
    }

    private suspend fun executeEngine(
        request: ScriptTaskRequest,
        broker: ScriptToolBroker,
        cancellation: ScriptCancellation,
    ): ScriptExecutionResult = try {
        withContext(Dispatchers.Default + NonCancellable) {
            engine.execute(
                ScriptExecutionRequest(
                    request.source,
                    request.limits,
                    broker,
                    cancellation,
                ),
            )
        }
    } catch (_: CancellationException) {
        ScriptExecutionResult.Failure(
            ScriptFailureKind.CANCELLED,
            "script task cancelled",
            ScriptExecutionStats(callsUsed = broker.callsUsed),
        )
    } catch (_: Throwable) {
        ScriptExecutionResult.Failure(
            ScriptFailureKind.ENGINE,
            "script engine failed",
            requestStats(broker),
        )
    }

    private suspend fun finishWithoutExecution(
        context: PtcTaskContext,
        started: Instant,
        error: ScriptRequestException,
    ): ScriptReceipt {
        val receipt = ScriptReceipt(
            factId = receiptFactId(context.taskId, context.generation),
            taskId = context.taskId,
            generation = context.generation,
            state = ScriptTerminalState.FAILED,
            receipt = "ptc:failed:0:" + digest(error.code, error.message),
            committedAt = wallClock.now(),
            content = ScriptReceiptContent(result = null,
            trace = emptyList(),
            failure = ScriptFailureRecord(ScriptFailureKind.INVALID_REQUEST, error.message),
            durationMillis = 0,
            callsUsed = 0),
        )
        withContext(NonCancellable) { receipts.commit(receipt) }
        log(context, receipt, started, LogLevel.WARN)
        return receipt
    }

    private fun receipt(
        context: PtcTaskContext,
        started: Instant,
        broker: ScriptToolBroker,
        result: ScriptExecutionResult,
        job: Job?,
    ): ScriptReceipt {
        val cancelled = wasCancelled(job, result)
        val state = when {
            cancelled -> ScriptTerminalState.CANCELLED
            result is ScriptExecutionResult.Success -> ScriptTerminalState.COMPLETED
            else -> ScriptTerminalState.FAILED
        }
        val failure = when {
            cancelled -> ScriptFailureRecord(ScriptFailureKind.CANCELLED, "script task cancelled")
            result is ScriptExecutionResult.Failure ->
                ScriptFailureRecord(result.kind, result.message.take(MAX_FAILURE_CHARS))
            else -> null
        }
        val value = (result as? ScriptExecutionResult.Success)?.value
        return ScriptReceipt(
            factId = receiptFactId(context.taskId, context.generation),
            taskId = context.taskId,
            generation = context.generation,
            state = state,
            receipt = "ptc:${state.name.lowercase()}:${broker.callsUsed}:" +
                digest(value?.canonical() ?: failure?.kind?.name.orEmpty()),
            committedAt = wallClock.now(),
            content = ScriptReceiptContent(result = value,
            trace = broker.trace,
            failure = failure,
            durationMillis = result.stats.durationMillis,
            callsUsed = broker.callsUsed),
        )
    }

    private fun requestStats(broker: ScriptToolBroker) =
        ScriptExecutionStats(callsUsed = broker.callsUsed)

    private fun wasCancelled(job: Job?, result: ScriptExecutionResult): Boolean = job?.isActive == false ||
        result is ScriptExecutionResult.Failure && result.kind == ScriptFailureKind.CANCELLED

    private fun cancelledStats(broker: ScriptToolBroker): ScriptExecutionResult.Failure =
        ScriptExecutionResult.Failure(
            ScriptFailureKind.CANCELLED,
            "script task cancelled",
            ScriptExecutionStats(callsUsed = broker.callsUsed),
        )

    private fun deadlineNanos(durationNanos: Long): Long {
        val now = monotonicClock.nowNanos()
        return if (durationNanos > Long.MAX_VALUE - now) Long.MAX_VALUE else now + durationNanos
    }

    private fun digest(vararg values: String): String =
        Digests.sha256HexUtf8(values.joinToString("\u0000")).take(16)

    private fun log(
        context: PtcTaskContext,
        receipt: ScriptReceipt,
        started: Instant,
        level: LogLevel = if (receipt.state == ScriptTerminalState.COMPLETED) {
            LogLevel.INFO
        } else {
            LogLevel.WARN
        },
    ) {
        val fields = JsonValue.Object.Builder()
            .put("owner", "services.script")
            .put("state", receipt.state.name.lowercase())
            .put("calls_used", receipt.callsUsed.toLong())
            .put("duration_ms", receipt.durationMillis)
            .put("receipt_id", receipt.factId)
            .put("wall_delta_ms", maxOf(0, java.time.Duration.between(started, receipt.committedAt).toMillis()))
            .apply { receipt.failure?.let { put("failure_kind", it.kind.name) } }
            .build()
        try {
            logger.log(LogRecord(level, "script.ptc.finished", context.taskId, fields = fields))
        } catch (_: Exception) {
            // Diagnostics must not change the committed task fact.
        }
    }

    private companion object {
        const val MAX_FAILURE_CHARS = 300
    }
}

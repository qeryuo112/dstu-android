package com.deepstudent.app.execution

import com.deepstudent.app.storage.AndroidOperationLogger
import com.deepstudent.core.common.id.TaskId
import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.services.execution.api.ExecutionFailureCode
import com.deepstudent.services.execution.api.ShellCommand
import com.deepstudent.services.execution.api.ShellConsole
import com.deepstudent.services.execution.api.ShellResult
import com.deepstudent.services.execution.api.ShellRunState
import com.deepstudent.services.runtime.api.BusinessCompletionSource
import com.deepstudent.services.runtime.api.RuntimeTaskContext
import com.deepstudent.services.runtime.api.RuntimeTaskGeneration
import com.deepstudent.services.runtime.api.RuntimeTaskHandler
import com.deepstudent.services.runtime.api.TaskKind
import com.deepstudent.services.runtime.api.TaskSubmission
import com.deepstudent.services.runtime.backend.CapabilityBroker
import com.deepstudent.services.runtime.backend.RuntimeController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** Only an opaque handoff key goes into runtime.db. An app death makes the invocation unreplayable. */
internal class MainShellTasks(private val router: ShellRouter, private val broker: CapabilityBroker) {
    private val submitLock = Mutex()
    private val queued = AtomicReference<PendingShell?>()
    private val run = MutableStateFlow(ShellRunState())
    val handler = RuntimeTaskHandler(::execute)

    fun console(controller: RuntimeController, owner: CoroutineScope): ShellConsole = object : ShellConsole {
        override val state = run.asStateFlow()

        override suspend fun submit(command: ShellCommand) = withContext(Dispatchers.IO + NonCancellable) {
            submitLock.withLock {
                if (!run.value.waitingOrRunning) start(command, controller, owner)
            }
        }

        override suspend fun revoke(taskId: String) = withContext(Dispatchers.IO + NonCancellable) {
            if (run.value.taskId != taskId || !run.value.waitingOrRunning) return@withContext
            val snapshot = controller.snapshot(TaskId.parse(taskId)) ?: return@withContext
            val task = RuntimeTaskGeneration(snapshot.taskId, snapshot.ownership.generation)
            if (!broker.revoke(task)) controller.cancelIfGeneration(task.taskId, task.generation)
        }
    }

    private suspend fun start(command: ShellCommand, controller: RuntimeController, owner: CoroutineScope) {
        val routed = try {
            router.prepare(command)
        } catch (failure: Exception) {
            AndroidOperationLogger.warn("shell.prepare_failed")
            null
        }
        if (routed == null) {
            run.value = ShellRunState(result = ShellResult.Unavailable(ExecutionFailureCode.PROCESS_START_FAILED))
            return
        }
        val pending = PendingShell(UUID.randomUUID().toString(), routed)
        check(queued.compareAndSet(null, pending))
        run.value = ShellRunState(waitingOrRunning = true, scope = routed.scope)
        try {
            val handle = controller.submit(TaskSubmission(KIND, JsonValue.text(pending.key)))
            owner.launch {
                controller.observeSnapshots(handle.taskId).first { it?.state?.terminal == true }
                if (queued.compareAndSet(pending, null)) {
                    run.value = ShellRunState(result = ShellResult.Unavailable(ExecutionFailureCode.PROCESS_START_FAILED))
                }
            }
        } catch (failure: Exception) {
            queued.compareAndSet(pending, null)
            run.value = ShellRunState(result = ShellResult.Unavailable(ExecutionFailureCode.PROCESS_START_FAILED))
            throw failure
        }
    }

    private suspend fun execute(context: RuntimeTaskContext, payload: JsonValue) {
        val pending = checkNotNull(queued.get()) { "missing live shell owner" }
        check((payload as? JsonValue.Text)?.value == pending.key && queued.compareAndSet(pending, null)) { "stale shell owner" }
        val address = ShellRunState(context.taskId.value, true, pending.routed.scope)
        run.value = address
        var result: ShellResult = ShellResult.Blocked("CANCELLED")
        try {
            result = pending.routed.run(context, broker)
            context.checkpoint(shellFacts(pending.routed, result))
            check(result is ShellResult.Exited) { "shell command did not execute" }
        } catch (cancelled: CancellationException) {
            result = ShellResult.Blocked("CANCELLED")
            throw cancelled
        } finally {
            run.value = address.copy(waitingOrRunning = false, result = result)
        }
    }

    private fun shellFacts(routed: RoutedShell, result: ShellResult): JsonValue.Object = JsonValue.Object.Builder().apply {
        put("target", routed.scope.target.name)
        put("resource", routed.scope.resource)
        put("uid_expected", routed.scope.uid.toLong())
        when (result) {
            is ShellResult.Exited -> {
                put("uid_observed", result.receipt.observedUid.toLong())
                put("exit_code", result.receipt.exitCode.toLong())
                put("duration_ms", result.receipt.durationMillis)
                put("output_truncated", result.receipt.output.stdoutTruncated || result.receipt.output.stderrTruncated)
            }

            is ShellResult.Unavailable -> put("reason", result.code.name)

            is ShellResult.Blocked -> put("reason", result.reason)
        }
    }.build()

    private class PendingShell(val key: String, val routed: RoutedShell)

    companion object {
        val KIND = TaskKind.parse("shell.console")
        val noBusinessCompletion = object : BusinessCompletionSource {
            override suspend fun find(taskId: TaskId, generation: Long) = null
            override suspend fun pending(consumerId: String, limit: Int) =
                emptyList<com.deepstudent.services.runtime.api.BusinessCompletion>()
            override suspend fun acknowledge(consumerId: String, factId: String) = Unit
        }
    }
}

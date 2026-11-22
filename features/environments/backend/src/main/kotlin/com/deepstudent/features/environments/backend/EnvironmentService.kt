package com.deepstudent.features.environments.backend

import com.deepstudent.features.environments.api.EnvironmentCommandException
import com.deepstudent.features.environments.api.EnvironmentFailure
import com.deepstudent.features.environments.api.EnvironmentFailureCode
import com.deepstudent.features.environments.api.EnvironmentManager
import com.deepstudent.features.environments.api.EnvironmentPhase
import com.deepstudent.features.environments.api.PackageRecipeStatus
import com.deepstudent.features.environments.api.EnvironmentSnapshot
import com.deepstudent.features.environments.api.EnvironmentTaskId
import com.deepstudent.features.environments.api.RootfsArchiveSource
import com.deepstudent.features.environments.api.RootfsManifest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Process-owned jobs. Cancelling a caller/UI observation never cancels an accepted installation. */
class EnvironmentService(
    private val coordinator: EnvironmentCoordinator,
    initialJournal: EnvironmentJournal?,
    private val scope: CoroutineScope,
    private val newTaskId: () -> String = { UUID.randomUUID().toString() },
) : EnvironmentManager {
    private data class Operation(val id: EnvironmentTaskId, val job: Job)
    private val lock = Any()
    private var current: Operation? = null
    private val mutable = MutableStateFlow(restoredEnvironmentSnapshot(initialJournal))
    override val state: StateFlow<EnvironmentSnapshot> = mutable.asStateFlow()

    override suspend fun installRootfs(manifest: RootfsManifest, source: RootfsArchiveSource): EnvironmentTaskId =
        start(EnvironmentPhase.VERIFYING) { task, update -> coordinator.installRootfs(task, manifest, source, update) }

    override suspend fun installPackage(recipeId: String): EnvironmentTaskId =
        start(EnvironmentPhase.INSTALLING_PACKAGE, recipeId) { task, update -> coordinator.installRecipe(task, recipeId, update) }

    override suspend fun retry(source: RootfsArchiveSource?): EnvironmentTaskId =
        start(EnvironmentPhase.VERIFYING) { task, update -> coordinator.retry(task, null, source, update) }

    override suspend fun cancel(taskId: EnvironmentTaskId) {
        val operation = synchronized(lock) { current?.takeIf { it.id == taskId } } ?: return
        EnvironmentLog.event("cancel-requested", "task=${taskId.value}")
        operation.job.cancel()
        operation.job.join()
    }

    private fun start(
        phase: EnvironmentPhase,
        recipeId: String? = null,
        block: suspend (EnvironmentTaskId, EnvironmentUpdate) -> EnvironmentJournal,
    ): EnvironmentTaskId {
        scope.coroutineContext.ensureActive()
        val operation = synchronized(lock) {
            if (current != null) rejectBusy()
            val task = EnvironmentTaskId(newTaskId())
            val job = scope.launch(start = CoroutineStart.LAZY) { execute(task, block) }
            Operation(task, job).also { accepted ->
                current = accepted
                mutable.value = mutable.value.copy(phase = phase, runtime = mutable.value.runtime.copy(
                    taskId = task, failure = null, recoverable = false,
                    recipes = mutable.value.recipes.map {
                        if (it.id == recipeId) it.copy(status = PackageRecipeStatus.INSTALLING) else it
                    },
                ))
                job.invokeOnCompletion { failure -> complete(accepted, failure) }
            }
        }
        EnvironmentLog.event("task-accepted", "task=${operation.id.value} phase=$phase")
        operation.job.start()
        return operation.id
    }

    private suspend fun execute(
        task: EnvironmentTaskId,
        block: suspend (EnvironmentTaskId, EnvironmentUpdate) -> EnvironmentJournal,
    ) {
        var terminal: EnvironmentSnapshot? = null
        try {
            block(task) { journal, phase, progress ->
                val next = environmentSnapshot(journal, phase, progress, task)
                if (phase.isRunning()) mutable.value = next else terminal = next
            }
        } catch (cancelled: CancellationException) {
            terminal = terminal ?: cancelledSnapshot()
            throw cancelled
        } catch (failure: Exception) {
            terminal = failureSnapshot(failure)
            EnvironmentLog.failure("task-failed", failure, "task=${task.value} code=${terminal.failure?.code}")
        } finally {
            // Publish a terminal receipt only after its process owner has returned and
            // the slot is available. Observing READY must permit the next command.
            synchronized(lock) {
                if (current?.id == task) {
                    current = null
                    terminal?.let { mutable.value = it }
                    EnvironmentLog.event("task-released", "task=${task.value} phase=${mutable.value.phase}")
                }
            }
        }
    }

    private fun failureSnapshot(failure: Exception): EnvironmentSnapshot {
        val safe = (failure as? EnvironmentCommandException)?.failure?.let {
            it.copy(message = EnvironmentFailureMessages.forCode(it.code))
        } ?: EnvironmentFailure(EnvironmentFailureCode.UNKNOWN, "environment operation failed", true)
        return mutable.value.copy(phase = EnvironmentPhase.FAILED, runtime = mutable.value.runtime.copy(
            failure = safe, recoverable = safe.recoverable,
        ))
    }

    private fun cancelledSnapshot(): EnvironmentSnapshot {
        val cancelled = EnvironmentFailure(EnvironmentFailureCode.CANCELLED, "environment operation was cancelled", true)
        return mutable.value.copy(phase = EnvironmentPhase.CANCELLED, runtime = mutable.value.runtime.copy(
            failure = cancelled, recoverable = true,
        ))
    }

    private fun complete(operation: Operation, failure: Throwable?) = synchronized(lock) {
        if (current !== operation) return@synchronized
        if (failure is CancellationException && mutable.value.phase.isRunning()) {
            val cancelled = EnvironmentFailure(EnvironmentFailureCode.CANCELLED, "environment operation was cancelled", true)
            mutable.value = mutable.value.copy(phase = EnvironmentPhase.CANCELLED, runtime = mutable.value.runtime.copy(
                failure = cancelled, recoverable = true,
            ))
        }
        current = null
        EnvironmentLog.event("task-released", "task=${operation.id.value} phase=${mutable.value.phase}")
    }

    private fun rejectBusy(): Nothing {
        EnvironmentLog.event("task-rejected", "code=BUSY")
        throw EnvironmentCommandException(EnvironmentFailure(EnvironmentFailureCode.BUSY, "another environment operation is running", true))
    }
}

private fun EnvironmentPhase.isRunning(): Boolean = this in setOf(
    EnvironmentPhase.VERIFYING, EnvironmentPhase.EXTRACTING, EnvironmentPhase.HEALTH_CHECKING,
    EnvironmentPhase.ACTIVATING, EnvironmentPhase.INSTALLING_PACKAGE,
)

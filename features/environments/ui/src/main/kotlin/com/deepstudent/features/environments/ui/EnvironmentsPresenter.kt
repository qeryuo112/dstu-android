package com.deepstudent.features.environments.ui

import com.deepstudent.features.environments.api.EnvironmentCatalog
import com.deepstudent.features.environments.api.EnvironmentCommandException
import com.deepstudent.features.environments.api.EnvironmentFailure
import com.deepstudent.features.environments.api.EnvironmentFailureCode
import com.deepstudent.features.environments.api.EnvironmentManager
import com.deepstudent.features.environments.api.RootfsArchiveSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Observes the process-owned task and submits commands. Never opens archives, files or processes. */
class EnvironmentsPresenter(
    private val manager: EnvironmentManager,
    scope: CoroutineScope,
    private val downloadSource: RootfsArchiveSource? = null,
    private val archivePicker: suspend () -> RootfsArchiveSource?,
) {
    private val presenterScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
    private val mutable = MutableStateFlow(EnvironmentUiState(manager.state.value, downloadAvailable = downloadSource != null))
    private var submitting = false
    private var stopped = false
    private var lastSource: RootfsArchiveSource? = null
    val state: StateFlow<EnvironmentUiState> = mutable.asStateFlow()

    init {
        presenterScope.launch {
            manager.state.collect { snapshot ->
                mutable.update { it.copy(snapshot = snapshot, busy = submitting || snapshot.phase.isBusy()) }
            }
        }
    }

    val actions = EnvironmentActions(
        installRootfs = ::pickAndInstall,
        downloadRootfs = ::downloadAndInstall,
        retry = ::retry,
        cancel = ::cancel,
        installRecipe = { id -> submit("package-install") { manager.installPackage(id) } },
        dismissFailure = { mutable.update { it.copy(actionFailure = null) } },
        close = ::stop,
    )

    fun stop() {
        stopped = true
        presenterScope.cancel()
        lastSource = null
        EnvironmentUiLog.event("observer-detached")
    }

    private fun pickAndInstall() = submit("rootfs-import") {
        val source = archivePicker() ?: return@submit
        lastSource = source
        manager.installRootfs(EnvironmentCatalog.UbuntuNobleAarch64, source)
    }

    private fun downloadAndInstall() = submit("rootfs-download") {
        val source = downloadSource ?: return@submit
        lastSource = source
        manager.installRootfs(EnvironmentCatalog.UbuntuNobleAarch64, source)
    }

    // Continue only the selected source (or a verified local archive). Never switch sources or
    // open the document picker implicitly, including after a network failure or recreation.
    private fun retry() = submit("operation-retry") {
        manager.retry(lastSource)
    }

    private fun cancel() {
        if (stopped) return
        val task = mutable.value.snapshot.taskId ?: return
        // Cancellation remains available while a long-running task is busy.
        presenterScope.launch {
            try {
                manager.cancel(task)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                report("task-cancel", failure)
            }
        }
    }

    private fun submit(label: String, block: suspend () -> Unit) {
        if (stopped || mutable.value.busy) return
        submitting = true
        mutable.update { it.copy(busy = true, actionFailure = null) }
        EnvironmentUiLog.event("command-submitting", "command=$label")
        presenterScope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                report(label, failure)
            } finally {
                submitting = false
                mutable.update { it.copy(busy = it.snapshot.phase.isBusy()) }
            }
        }
    }

    private fun report(label: String, failure: Exception) {
        val safe = (failure as? EnvironmentCommandException)?.failure?.let {
            it.copy(message = "环境操作未完成（${it.code.name}）")
        } ?: EnvironmentFailure(EnvironmentFailureCode.UNKNOWN, "环境操作未完成，请重试", true)
        mutable.update { it.copy(actionFailure = safe) }
        EnvironmentUiLog.event("command-rejected", "command=$label code=${safe.code} type=${failure.javaClass.simpleName}")
    }
}


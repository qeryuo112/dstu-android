package com.deepstudent.features.execution.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.deepstudent.services.execution.api.ExecutionCommand
import com.deepstudent.services.execution.api.ExecutionFailure
import com.deepstudent.services.execution.api.ExecutionFailureCode
import com.deepstudent.services.execution.api.ExecutionRequest
import com.deepstudent.services.execution.api.ExecutionResult
import com.deepstudent.services.execution.api.ExecutionSystem
import com.deepstudent.services.execution.api.ShizukuStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ShizukuUiState(
    val status: ShizukuStatus = ShizukuStatus(),
    val runningCommand: ExecutionCommand? = null,
    val result: ExecutionResult? = null,
    val permissionRequestInFlight: Boolean = false,
)

/**
 * UI state coordinator. [system] is application-scoped: this ViewModel starts
 * the monitor lease but never stops it, because clearing one screen must not
 * remove binder listeners or abort an execution owned by another screen.
 */
class ShizukuExecutionViewModel(
    private val system: ExecutionSystem,
    private val taskIdFactory: () -> String = {
        "shizuku-ui-${System.currentTimeMillis()}"
    },
) : ViewModel() {
    private val local = MutableStateFlow(ShizukuUiState())
    private var executionJob: Job? = null

    val uiState: StateFlow<ShizukuUiState> = combine(system.status, local) { status, state ->
        state.copy(status = status)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = local.value.copy(status = system.status.value),
    )

    init {
        system.start()
    }

    fun refresh() {
        viewModelScope.launch { system.refresh() }
    }

    fun requestPermission() {
        if (!system.status.value.canRequestPermission) return
        if (local.value.permissionRequestInFlight || local.value.runningCommand != null) return
        local.update { it.copy(permissionRequestInFlight = true) }
        system.requestPermission {
            local.update { state -> state.copy(permissionRequestInFlight = false) }
        }
    }

    fun verifyIdentity() = run(ExecutionCommand.Identity)

    fun listPackages() = run(ExecutionCommand.PackageList)

    /**
     * Cancels the coroutine that owns the process. The executor destroys the
     * acquired process/PFDs before cancellation propagates. A late Binder
     * creation reply retains its own cleanup owner; UI never owns those handles.
     */
    fun cancelExecution() {
        executionJob?.cancel()
    }

    private fun run(command: ExecutionCommand) {
        if (!system.status.value.canExecute || local.value.runningCommand != null) return
        local.update { it.copy(runningCommand = command, result = null) }
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                val result = system.execute(
                    ExecutionRequest(
                        taskId = taskIdFactory(),
                        callerId = "features.execution.ui",
                        command = command,
                    ),
                )
                local.update { it.copy(result = result) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                local.update {
                    it.copy(
                        result = ExecutionResult.Failure(
                            ExecutionFailure(ExecutionFailureCode.IO_FAILED),
                        ),
                    )
                }
            }
        }
        job.invokeOnCompletion { cause ->
            local.update { state ->
                state.copy(
                    runningCommand = null,
                    result = if (cause is CancellationException) {
                        ExecutionResult.Failure(ExecutionFailure(ExecutionFailureCode.CANCELLED))
                    } else {
                        state.result
                    },
                )
            }
        }
        executionJob = job
        job.start()
    }
}

package com.deepstudent.services.execution.api

import kotlinx.coroutines.flow.StateFlow

enum class ShizukuConnectionState {
    NOT_INSTALLED,
    NOT_RUNNING,
    PERMISSION_REQUIRED,
    READY,
    REVOKED,
    BINDER_DEAD,
    PROTOCOL_ERROR,
    UNTRUSTED_BACKEND,
    CLOSED,
}

enum class ShizukuIdentity {
    UNKNOWN,
    UNVERIFIED,
    SHELL,
    ROOT,
    MISMATCH,
    UNTRUSTED,
}

data class ShizukuStatus(
    val state: ShizukuConnectionState = ShizukuConnectionState.NOT_INSTALLED,
    val installedVersionName: String? = null,
    val serviceVersion: Int? = null,
    val backendUid: Int? = null,
    val observedUid: Int? = null,
    val diagnostic: ExecutionFailureCode? = null,
) {
    val canExecute: Boolean
        get() = state == ShizukuConnectionState.READY

    val canRequestPermission: Boolean
        get() = state == ShizukuConnectionState.PERMISSION_REQUIRED ||
            state == ShizukuConnectionState.REVOKED

    val identity: ShizukuIdentity
        get() {
            val backend = backendUid ?: return ShizukuIdentity.UNKNOWN
            if (backend != 0 && backend != 2000) return ShizukuIdentity.UNTRUSTED
            val observed = observedUid ?: return ShizukuIdentity.UNVERIFIED
            if (observed != backend) return ShizukuIdentity.MISMATCH
            return if (backend == 0) ShizukuIdentity.ROOT else ShizukuIdentity.SHELL
        }
}

enum class ExecutionFailureCode {
    NOT_INSTALLED,
    SERVICE_NOT_RUNNING,
    PERMISSION_REQUIRED,
    PERMISSION_REVOKED,
    BINDER_DEAD,
    BINDER_PROTOCOL_FAILED,
    UNTRUSTED_BACKEND_UID,
    UID_MISMATCH,
    INVALID_REQUEST,
    PROCESS_START_FAILED,
    IO_FAILED,
    TIMEOUT,
    CANCELLED,
    SYSTEM_CLOSED,
}

/**
 * Fixed, read-only diagnostics. The contract deliberately has no free-form
 * argv or shell string: callers select a typed command and the executor rejects
 * any object whose [argv] no longer matches the fixed list.
 */
sealed class ExecutionCommand(
    val commandId: String,
    private val fixedArgv: List<String>,
) {
    // Return a snapshot: mutating a List through a Java/MutableList cast must not
    // change the next execution, even between validation and process creation.
    val argv: List<String>
        get() = fixedArgv.toList()

    data object Identity : ExecutionCommand("id", listOf("id"))

    /** Read-only package query; it never installs, uninstalls or changes state. */
    data object PackageList : ExecutionCommand(
        "pm.list.packages",
        listOf("pm", "list", "packages"),
    )
}

data class ExecutionRequest(
    val taskId: String,
    val callerId: String,
    val command: ExecutionCommand,
)

data class ExecutionAudit(
    val taskId: String,
    val callerId: String,
    val commandId: String,
    val argv: List<String>,
)

data class ExecutionIdentity(
    val backendUid: Int,
    val observedUid: Int?,
)

data class ExecutionOutput(
    val stdout: String,
    val stderr: String,
    val stdoutTruncated: Boolean,
    val stderrTruncated: Boolean,
)

data class ExecutionReceipt(
    val audit: ExecutionAudit,
    val identity: ExecutionIdentity,
    val output: ExecutionOutput,
    val exitCode: Int,
    val durationMillis: Long,
)

data class ExecutionFailure(
    val code: ExecutionFailureCode,
    val exitCode: Int? = null,
)

sealed interface ExecutionResult {
    data class Success(val receipt: ExecutionReceipt) : ExecutionResult

    data class Failure(val failure: ExecutionFailure) : ExecutionResult
}

/**
 * The only surface exposed to UI and future orchestration. Implementations own
 * binder, process and descriptor lifetimes.
 *
 * Lifecycle: the instance is application-scoped and may outlive any Activity.
 * [start] and [stop] are idempotent monitor leases; they never cancel an
 * execution already owned by a caller. Active executions retain their own
 * monitor lease even after [stop]. [close] is terminal: it rejects new work,
 * aborts owners and removes binder listeners without blocking the UI on IPC.
 * Each execution completes only after its acquired descriptors are released.
 *
 * Cancellation belongs to the caller coroutine. Its acquired process and
 * descriptors are destroyed/closed before cancellation propagates. A Binder
 * newProcess transaction itself cannot be interrupted: if its reply arrives
 * after cancellation/timeout/close, the transaction owner destroys and closes
 * that late handle instead of handing it to a departed caller.
 */
interface ExecutionSystem {
    val status: StateFlow<ShizukuStatus>

    fun start()

    fun stop()

    suspend fun refresh()

    fun requestPermission(onResult: (Boolean) -> Unit)

    suspend fun execute(request: ExecutionRequest): ExecutionResult

    fun close()
}

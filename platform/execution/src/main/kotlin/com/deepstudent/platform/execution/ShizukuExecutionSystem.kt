package com.deepstudent.platform.execution

import android.content.Context
import com.deepstudent.services.execution.api.ExecutionFailure
import com.deepstudent.services.execution.api.ExecutionFailureCode
import com.deepstudent.services.execution.api.ExecutionRequest
import com.deepstudent.services.execution.api.ExecutionResult
import com.deepstudent.services.execution.api.ExecutionSystem
import com.deepstudent.services.execution.api.ShizukuConnectionState
import com.deepstudent.services.execution.api.ShizukuStatus
import kotlinx.coroutines.flow.StateFlow

/** Application-owned service. Screen monitor leases and execution owners are independent. */
class ShizukuExecutionSystem internal constructor(
    private val authorizer: ShizukuAuthorizer,
    private val executor: ShizukuExecutor,
) : ExecutionSystem {
    private val lock = Any()
    private var started = false
    private var closed = false
    private var executions = 0

    override val status: StateFlow<ShizukuStatus> = authorizer.status

    init {
        // StateFlow is conflated: a fast READY -> REVOKED -> READY sequence can
        // be missed by a collector. Invalidation is synchronous with publication.
        authorizer.onInvalidated = executor::abortAll
    }

    override fun start() {
        synchronized(lock) {
            if (closed || started) return
            started = true
            authorizer.start()
        }
    }

    override fun stop() {
        synchronized(lock) {
            started = false
            if (executions == 0) authorizer.stop()
        }
    }

    override suspend fun refresh() {
        authorizer.refresh()
    }

    override fun requestPermission(onResult: (Boolean) -> Unit) {
        if (synchronized(lock) { closed }) {
            onResult(false)
            return
        }
        start()
        authorizer.requestPermission(onResult)
    }

    override suspend fun execute(request: ExecutionRequest): ExecutionResult {
        synchronized(lock) {
            if (closed) return failure(ExecutionFailureCode.SYSTEM_CLOSED)
            executions++
            authorizer.start()
        }
        try {
            authorizer.refresh()
            val checkpoint = executor.checkpoint()
            status.value.state.failureCode()?.let { return failure(it) }
            val binding = authorizer.authorizedBinding()
                ?: return failure(status.value.state.failureCode() ?: ExecutionFailureCode.BINDER_DEAD)
            val result = executor.execute(request, binding, checkpoint)
            updateAuthorization(binding, result)
            return result
        } finally {
            synchronized(lock) {
                executions--
                if (!started && executions == 0) authorizer.stop()
            }
        }
    }

    private suspend fun updateAuthorization(binding: ShizukuBinding, result: ExecutionResult) {
        when (result) {
            is ExecutionResult.Success -> result.receipt.identity.observedUid?.let {
                authorizer.recordVerifiedUid(binding, it)
            }
            is ExecutionResult.Failure -> when (result.failure.code) {
                ExecutionFailureCode.UID_MISMATCH -> authorizer.clearVerifiedUid(binding)
                in CONNECTION_FAILURES -> authorizer.refresh()
                else -> Unit
            }
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            started = false
            executor.close()
            authorizer.close()
        }
    }

    private fun failure(code: ExecutionFailureCode) = ExecutionResult.Failure(ExecutionFailure(code))

    private companion object {
        val CONNECTION_FAILURES = setOf(
            ExecutionFailureCode.BINDER_DEAD,
            ExecutionFailureCode.PERMISSION_REVOKED,
            ExecutionFailureCode.PERMISSION_REQUIRED,
        )
    }
}

private val connectionFailures = mapOf(
    ShizukuConnectionState.NOT_INSTALLED to ExecutionFailureCode.NOT_INSTALLED,
    ShizukuConnectionState.NOT_RUNNING to ExecutionFailureCode.SERVICE_NOT_RUNNING,
    ShizukuConnectionState.PERMISSION_REQUIRED to ExecutionFailureCode.PERMISSION_REQUIRED,
    ShizukuConnectionState.REVOKED to ExecutionFailureCode.PERMISSION_REVOKED,
    ShizukuConnectionState.BINDER_DEAD to ExecutionFailureCode.BINDER_DEAD,
    ShizukuConnectionState.PROTOCOL_ERROR to ExecutionFailureCode.BINDER_PROTOCOL_FAILED,
    ShizukuConnectionState.UNTRUSTED_BACKEND to ExecutionFailureCode.UNTRUSTED_BACKEND_UID,
    ShizukuConnectionState.CLOSED to ExecutionFailureCode.SYSTEM_CLOSED,
)

internal fun ShizukuConnectionState.failureCode(): ExecutionFailureCode? = connectionFailures[this]

object ShizukuExecutionFactory {
    /** Call from the main-process graph's lazy property, never from engine/isolated graphs. */
    fun create(context: Context): ExecutionSystem {
        val log = AndroidExecutionLogSink
        val source = AndroidShizukuConnectionSource(context, log)
        val authorizer = ShizukuAuthorizer(source, log)
        val executor = ShizukuExecutor(log = log)
        return ShizukuExecutionSystem(authorizer, executor)
    }
}

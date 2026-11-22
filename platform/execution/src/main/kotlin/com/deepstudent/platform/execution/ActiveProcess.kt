package com.deepstudent.platform.execution

import com.deepstudent.services.execution.api.ExecutionFailureCode
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Registered before newProcess: a late Binder reply still has a cleanup owner. */
internal class ActiveProcess(
    timeoutMillis: Long,
    private val log: ExecutionLogSink,
    private val correlationId: String,
) {
    private val remote = AtomicReference<RemoteProcessHandle?>()
    private val abortState = AtomicReference<ExecutionFailureCode?>()
    private val destroyRequested = AtomicBoolean(false)
    private val closeRequested = AtomicBoolean(false)
    private val destroyed = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    val deadlineNanos: Long = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
    val handle: RemoteProcessHandle
        get() = checkNotNull(remote.get())
    val abortCode: ExecutionFailureCode?
        get() = abortState.get()

    fun attach(handle: RemoteProcessHandle) {
        check(remote.compareAndSet(null, handle))
        // A timed-out/cancelled start cannot hand a process back to an absent caller.
        if (abortCode != null || destroyRequested.get()) destroyOnce()
        if (closeRequested.get()) closeOnce()
    }

    /** Only records a signal; Binder callbacks and close() never block on remote IPC. */
    fun abort(code: ExecutionFailureCode) {
        abortState.compareAndSet(null, code)
    }

    fun throwIfAborted() {
        abortCode?.let { throw ProcessRunFailure(it) }
    }

    fun destroyOnce() {
        destroyRequested.set(true)
        val handle = remote.get() ?: return
        if (destroyed.compareAndSet(false, true)) {
            runCatching { handle.destroy() }.onFailure { logFailure("process_destroy_failed") }
        }
    }

    fun closeOnce() {
        closeRequested.set(true)
        val handle = remote.get() ?: return
        if (closed.compareAndSet(false, true)) {
            runCatching { handle.close() }.onFailure { logFailure("process_descriptor_close_failed") }
            log.emit(ExecutionLogEvent("process_owner_released", state = abortCode?.name, correlationId = correlationId))
        }
    }

    private fun logFailure(kind: String) {
        log.emit(ExecutionLogEvent(kind, state = abortCode?.name, correlationId = correlationId))
    }

    fun destroyAndClose() {
        try {
            destroyOnce()
        } finally {
            closeOnce()
        }
    }
}

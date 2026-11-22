package com.deepstudent.features.terminal.backend

import com.deepstudent.features.terminal.api.TerminalCommandException
import com.deepstudent.features.terminal.api.TerminalFailure
import com.deepstudent.features.terminal.api.TerminalPhase
import com.deepstudent.features.terminal.api.TerminalSessionId
import com.deepstudent.features.terminal.api.TerminalSessionSnapshot
import com.deepstudent.features.terminal.api.TerminalSize
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal class OwnedTerminalSession(
    val id: TerminalSessionId,
    val ordinal: Int,
    size: TerminalSize,
    private val process: TerminalProcessPort,
    screen: TerminalScreenPort,
    private val scope: CoroutineScope,
) {
    private val lock = Any()
    private val stateStore = TerminalSessionState(screen, size)
    private val ioPump = TerminalIoPump()
    private val writeMutex = Mutex()
    private val done = CompletableDeferred<Unit>()
    private val processClosed = AtomicBoolean(false)
    private var readerJob: Job? = null
    private var waiterJob: Job? = null
    private var stopping = false
    private var exited = false
    private var closeStarted = false

    val state: StateFlow<TerminalSessionSnapshot> = stateStore.state

    fun start() {
        synchronized(lock) {
            check(readerJob == null) { "Session already started" }
            stateStore.running()
            readerJob = scope.launch { readOutput() }
            waiterJob = scope.launch { waitForExit() }
        }
        TerminalLog.event("session-started", id)
    }

    private suspend fun readOutput() {
        try {
            ioPump.pump(process.input) { stateStore.output(it) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            TerminalLog.failure("output-failed", id, error)
            stateStore.failed(TerminalFailure.IO_FAILED)
            failProcess()
        }
    }

    private fun failProcess() {
        try {
            process.terminate()
        } catch (error: Exception) {
            TerminalLog.failure("output-stop-failed", id, error)
        }
    }

    private suspend fun waitForExit() {
        var code = -1
        var waitFailure: TerminalFailure? = null
        try {
            code = runInterruptible(Dispatchers.IO) { process.awaitExit() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            TerminalLog.failure("wait-failed", id, error)
            waitFailure = TerminalFailure.IO_FAILED
            failProcess()
        } finally {
            withContext(NonCancellable) {
                finish(code, waitFailure)
            }
        }
    }

    private suspend fun finish(code: Int, waitFailure: TerminalFailure?) {
        synchronized(lock) { exited = true }
        val reader = synchronized(lock) { readerJob }
        if (reader != null) {
            withTimeoutOrNull(OUTPUT_DRAIN_MS) { reader.join() }
        }
        closeProcess()
        if (reader != null) {
            reader.cancelAndJoin()
        }
        waitFailure?.let(stateStore::failed)
        stateStore.exited(code)
        done.complete(Unit)
        TerminalLog.event("session-exited", id, code)
    }

    suspend fun write(text: String) = withContext(Dispatchers.IO) {
        checkRunning()
        try {
            writeMutex.withLock { process.output.write(text.toByteArray(Charsets.UTF_8)) }
        } catch (error: Exception) {
            TerminalLog.failure("write-failed", id, error)
            stateStore.failed(TerminalFailure.IO_FAILED)
            failProcess()
            throw TerminalCommandException(TerminalFailure.IO_FAILED, error)
        }
    }

    suspend fun interrupt() = withContext(Dispatchers.IO) {
        checkRunning()
        try {
            process.interrupt()
        } catch (error: Exception) {
            TerminalLog.failure("interrupt-failed", id, error)
            throw TerminalCommandException(TerminalFailure.IO_FAILED, error)
        }
    }

    suspend fun resize(size: TerminalSize) = withContext(Dispatchers.IO) {
        checkRunning()
        if (!process.resize(size.rows, size.columns)) {
            stateStore.failed(TerminalFailure.RESIZE_FAILED)
            throw TerminalCommandException(TerminalFailure.RESIZE_FAILED)
        }
        stateStore.resized(size)
    }

    suspend fun terminate() = withContext(NonCancellable) {
        terminateProcess()
        withTimeoutOrNull(TERMINATE_WAIT_MS) { done.await() }
        if (!done.isCompleted) forceClose(TerminalFailure.STOP_FAILED)
    }

    private suspend fun terminateProcess() {
        val shouldSignal = synchronized(lock) {
            if (stopping || exited) {
                false
            } else {
                stopping = true
                true
            }
        }
        if (shouldSignal) {
            stateStore.stopping()
            withContext(Dispatchers.IO) { failProcess() }
            TerminalLog.event("session-stopping", id)
        }
    }

    suspend fun close() = withContext(NonCancellable) {
        val first = synchronized(lock) {
            if (closeStarted) false else {
                closeStarted = true
                true
            }
        }
        if (first) {
            terminateProcess()
            withTimeoutOrNull(CLOSE_WAIT_MS) { done.await() }
            if (!done.isCompleted) forceClose(TerminalFailure.STOP_FAILED)
        } else {
            withTimeoutOrNull(CLOSE_WAIT_MS) { done.await() }
        }
        if (!done.isCompleted) forceClose(TerminalFailure.STOP_FAILED) else closeProcess()
        TerminalLog.event("session-closed", id)
    }

    private suspend fun forceClose(failure: TerminalFailure) {
        TerminalLog.event("session-force-close", id)
        closeProcess()
        val reader = synchronized(lock) { readerJob }
        val waiter = synchronized(lock) { waiterJob }
        reader?.cancel()
        waiter?.cancel()
        withTimeoutOrNull(JOB_CANCEL_MS) {
            reader?.cancelAndJoin()
            waiter?.cancelAndJoin()
        }
        stateStore.failed(failure)
        done.complete(Unit)
    }

    private fun closeProcess() {
        if (!processClosed.compareAndSet(false, true)) return
        try {
            process.close()
        } catch (error: Exception) {
            TerminalLog.failure("close-failed", id, error)
        }
    }

    private fun checkRunning() {
        if (state.value.phase != TerminalPhase.RUNNING) {
            throw TerminalCommandException(TerminalFailure.SESSION_CLOSED)
        }
    }

    private companion object {
        const val OUTPUT_DRAIN_MS = 1_000L
        const val TERMINATE_WAIT_MS = 3_000L
        const val CLOSE_WAIT_MS = 3_500L
        const val JOB_CANCEL_MS = 500L
    }
}

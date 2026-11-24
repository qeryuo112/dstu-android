package com.deepstudent.platform.execution

import android.os.RemoteException
import com.deepstudent.services.execution.api.ExecutionCommand
import com.deepstudent.services.execution.api.ExecutionFailure
import com.deepstudent.services.execution.api.ExecutionFailureCode
import com.deepstudent.services.execution.api.ExecutionIdentity
import com.deepstudent.services.execution.api.ExecutionReceipt
import com.deepstudent.services.execution.api.ExecutionRequest
import com.deepstudent.services.execution.api.ExecutionResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

/**
 * Owns process creation, bounded dual-stream capture, cancellation and the
 * before/after backend UID probe. Only fixed, read-only argv is accepted; the
 * caller cannot inject a shell string.
 *
 * Every binder transaction and every blocking wait runs on [ioDispatcher]. The
 * UI calls [execute] from `viewModelScope` (the main dispatcher), so doing the
 * probe or `newProcess` on the caller thread would freeze the frame and could
 * trigger an ANR while Shizuku creates the process.
 */
internal class ShizukuExecutor(
    private val maxCaptureBytes: Int = DEFAULT_MAX_CAPTURE_BYTES,
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    private val log: ExecutionLogSink = AndroidExecutionLogSink,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val runner = ShizukuProcessRunner(maxCaptureBytes, ioDispatcher)
    private val active = mutableSetOf<ActiveProcess>()
    private var closed = false
    private var generation = 0L
    private var invalidationCode = ExecutionFailureCode.BINDER_DEAD

    init {
        require(maxCaptureBytes > 0) { "maxCaptureBytes must be positive" }
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }
    }

    fun checkpoint(): Long = synchronized(active) { generation }

    suspend fun execute(request: ExecutionRequest, binding: ShizukuBinding, checkpoint: Long = checkpoint()): ExecutionResult {
        validate(request)?.let { return it }
        return execute(ShizukuInvocation.diagnostic(request), binding, checkpoint)
    }

    suspend fun execute(request: ShizukuInvocation, binding: ShizukuBinding, checkpoint: Long): ExecutionResult {
        val owner = ActiveProcess(timeoutMillis, log, executionCorrelation(request.taskId))
        synchronized(active) {
            if (closed) return failure(ExecutionFailureCode.SYSTEM_CLOSED)
            if (generation != checkpoint) return failure(invalidationCode)
            active += owner
        }
        return executeOwned(request, binding, owner)
    }

    fun abortAll(code: ExecutionFailureCode) {
        synchronized(active) {
            generation++
            invalidationCode = code
            active.forEach { it.abort(code) }
        }
    }

    fun close() {
        synchronized(active) {
            if (closed) return
            closed = true
            abortAll(ExecutionFailureCode.SYSTEM_CLOSED)
        }
        runner.close()
    }

    private suspend fun executeOwned(request: ShizukuInvocation, binding: ShizukuBinding, owner: ActiveProcess): ExecutionResult {
        val started = System.nanoTime()
        var completed = false
        try {
            runner.start(owner) { startProcess(request, binding, owner) }
            logStart(request, binding.backendUid)
            val run = runner.run(owner, request.captureLimit(maxCaptureBytes))
            val result = runner.transact(owner) {
                finishProcess(request, binding, binding.backendUid, started, run)
            }
            owner.throwIfAborted()
            currentCoroutineContext().ensureActive()
            completed = result is ExecutionResult.Success
            return result
        } catch (error: ProcessRunFailure) {
            owner.abort(error.code)
            val code = checkNotNull(owner.abortCode)
            logAbort(request, binding.backendUid, code, started)
            return failure(code, error.exitCode)
        } catch (error: CancellationException) {
            owner.abort(ExecutionFailureCode.CANCELLED)
            logAbort(request, binding.backendUid, checkNotNull(owner.abortCode), started)
            throw error
        } finally {
            withContext(NonCancellable + ioDispatcher) {
                if (completed) owner.closeOnce() else owner.destroyAndClose()
            }
            synchronized(active) { active -= owner }
        }
    }

    private fun probeUid(binding: ShizukuBinding): UidProbe = try {
        val uid = binding.transport.uid()
        when {
            uid < 0 -> UidProbe(-1, ExecutionFailureCode.BINDER_DEAD)

            uid != binding.backendUid -> UidProbe(uid, ExecutionFailureCode.UID_MISMATCH)

            uid != 0 && uid != 2000 ->
                UidProbe(uid, ExecutionFailureCode.UNTRUSTED_BACKEND_UID)

            else -> UidProbe(uid)
        }
    } catch (error: SecurityException) {
        UidProbe(-1, ExecutionFailureCode.PERMISSION_REVOKED)
    } catch (error: RemoteException) {
        UidProbe(-1, ExecutionFailureCode.BINDER_DEAD)
    } catch (error: Exception) {
        UidProbe(-1, ExecutionFailureCode.BINDER_DEAD)
    }

    private fun startProcess(request: ShizukuInvocation, binding: ShizukuBinding, owner: ActiveProcess): RemoteProcessHandle {
        verifyBinding(binding)
        owner.throwIfAborted()
        return try {
            if (request.owned) {
                binding.transport.newOwnedProcess(request.argv.toTypedArray())
            } else {
                binding.transport.newProcess(request.argv.toTypedArray())
            }
        } catch (error: SecurityException) {
            throw ProcessRunFailure(ExecutionFailureCode.PERMISSION_REVOKED, error)
        } catch (error: RemoteException) {
            throw ProcessRunFailure(ExecutionFailureCode.BINDER_DEAD, error)
        } catch (error: IOException) {
            throw ProcessRunFailure(ExecutionFailureCode.PROCESS_START_FAILED, error)
        }
    }

    private fun verifyBinding(binding: ShizukuBinding): Int {
        if (!binding.isAlive()) throw ProcessRunFailure(ExecutionFailureCode.BINDER_DEAD)
        // UID/version transactions are protected server operations. Check the
        // fresh grant before probing UID, both before creation and after exit.
        if (!binding.transport.checkSelfPermission()) {
            throw ProcessRunFailure(ExecutionFailureCode.PERMISSION_REVOKED)
        }
        val probe = probeUid(binding)
        probe.failure?.let { throw ProcessRunFailure(it) }
        return probe.uid
    }

    private fun finishProcess(
        request: ShizukuInvocation,
        binding: ShizukuBinding,
        beforeUid: Int,
        started: Long,
        run: ProcessRun,
    ): ExecutionResult {
        val afterUid = verifyBinding(binding)
        if (afterUid != beforeUid) {
            throw ProcessRunFailure(ExecutionFailureCode.UID_MISMATCH, exitCode = run.exitCode)
        }
        val uidCheck = verifyObservedUid(request.probe, run.output.stdout, beforeUid)
        uidCheck.failure?.let { throw ProcessRunFailure(it, exitCode = run.exitCode) }
        if (request.probe == ShizukuInvocation.Probe.FIXED_IDENTITY && run.exitCode != 0) {
            throw ProcessRunFailure(ExecutionFailureCode.UID_MISMATCH, exitCode = run.exitCode)
        }
        val duration = elapsedMillis(started)
        logExit(request, beforeUid, run.exitCode, duration)
        return ExecutionResult.Success(
            ExecutionReceipt(
                audit = request.audit(),
                identity = ExecutionIdentity(beforeUid, uidCheck.observed),
                output = run.output,
                exitCode = run.exitCode,
                durationMillis = duration,
            ),
        )
    }

    private fun verifyObservedUid(probe: ShizukuInvocation.Probe, stdout: String, backendUid: Int): UidCheck {
        val observed = when (probe) {
            ShizukuInvocation.Probe.NONE -> return UidCheck(null)
            ShizukuInvocation.Probe.FIXED_IDENTITY -> parseUid(stdout)
            ShizukuInvocation.Probe.APPROVED_SHELL -> ShellProtocol.observedUid(stdout)
        } ?: return UidCheck(null, ExecutionFailureCode.UID_MISMATCH)
        return if (observed == backendUid) {
            UidCheck(observed)
        } else {
            UidCheck(observed, ExecutionFailureCode.UID_MISMATCH)
        }
    }

    private fun logStart(request: ShizukuInvocation, backendUid: Int) {
        log.emit(
            ExecutionLogEvent(
                kind = "process_start",
                backendUid = backendUid,
                correlationId = executionCorrelation(request.taskId),
                commandId = request.commandId,
            ),
        )
    }

    private fun logAbort(request: ShizukuInvocation, backendUid: Int, code: ExecutionFailureCode, started: Long) {
        log.emit(
            ExecutionLogEvent(
                kind = "process_abort",
                state = code.name,
                backendUid = backendUid,
                correlationId = executionCorrelation(request.taskId),
                commandId = request.commandId,
                durationMillis = elapsedMillis(started),
            ),
        )
    }

    private fun logExit(request: ShizukuInvocation, backendUid: Int, exitCode: Int, duration: Long) {
        log.emit(
            ExecutionLogEvent(
                kind = "process_exit",
                backendUid = backendUid,
                correlationId = executionCorrelation(request.taskId),
                commandId = request.commandId,
                exitCode = exitCode,
                durationMillis = duration,
            ),
        )
    }

    private fun validate(request: ExecutionRequest): ExecutionResult? {
        if (!validField(request.taskId) || !validField(request.callerId)) {
            return failure(ExecutionFailureCode.INVALID_REQUEST)
        }
        val expected = when (request.command) {
            ExecutionCommand.Identity -> listOf("id")
            ExecutionCommand.PackageList -> listOf("pm", "list", "packages")
        }
        return if (request.command.argv == expected) {
            null
        } else {
            failure(ExecutionFailureCode.INVALID_REQUEST)
        }
    }

    private fun validField(value: String): Boolean = value.isNotBlank() &&
        value.length <= 128 &&
        value.none { it.isISOControl() }

    private fun parseUid(stdout: String): Int? = UID_PATTERN.find(stdout)?.groupValues?.getOrNull(1)?.toIntOrNull()

    private fun failure(code: ExecutionFailureCode, exitCode: Int? = null): ExecutionResult =
        ExecutionResult.Failure(ExecutionFailure(code, exitCode))

    private fun elapsedMillis(started: Long): Long = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)

    private data class UidProbe(val uid: Int, val failure: ExecutionFailureCode? = null)

    private data class UidCheck(val observed: Int?, val failure: ExecutionFailureCode? = null)

    private companion object {
        const val DEFAULT_MAX_CAPTURE_BYTES = 256 * 1024
        const val DEFAULT_TIMEOUT_MILLIS = 20_000L
        val UID_PATTERN = Regex("""^uid=(\d+)(?:\(|\s|$)""")
    }
}

package com.deepstudent.platform.execution

import com.deepstudent.services.execution.api.ExecutionFailureCode
import com.deepstudent.services.execution.api.ExecutionOutput
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

internal data class ProcessRun(val exitCode: Int, val output: ExecutionOutput)

internal class ProcessRunFailure(
    val code: ExecutionFailureCode,
    cause: Throwable? = null,
    val exitCode: Int? = null,
) : Exception(cause)

/** Blocking IPC has bounded awaits; both pipes drain concurrently even past capture limits. */
internal class ShizukuProcessRunner(
    private val maxCaptureBytes: Int,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : Closeable {
    private val workers: ExecutorService = Executors.newCachedThreadPool { task ->
        Thread(task, "dstu-shizuku-io").apply { isDaemon = true }
    }

    suspend fun start(process: ActiveProcess, create: () -> RemoteProcessHandle) {
        transact(process) { process.attach(create()) }
    }

    suspend fun <T> transact(process: ActiveProcess, block: () -> T): T =
        runInterruptible(ioDispatcher) {
            process.throwIfAborted()
            val future = submit(block)
            try {
                await(future, process)
            } finally {
                future.cancel(true)
            }
        }

    suspend fun run(process: ActiveProcess): ProcessRun = runInterruptible(ioDispatcher) {
        val submitted = mutableListOf<Future<*>>()
        try {
            process.throwIfAborted()
            val stdout = submit { drainOwned(process, process.handle.stdout()) }.also { submitted += it }
            val stderr = submit { drainOwned(process, process.handle.stderr()) }.also { submitted += it }
            val exit = submit { process.handle.waitFor() }.also { submitted += it }
            val exitCode = await(exit, process)
            val output = await(stdout, process)
            val error = await(stderr, process)
            process.throwIfAborted()
            ProcessRun(
                exitCode,
                ExecutionOutput(output.text, error.text, output.truncated, error.truncated),
            )
        } finally {
            submitted.forEach { it.cancel(true) }
        }
    }

    override fun close() {
        workers.shutdownNow()
    }

    private fun <T> submit(block: () -> T): Future<T> = try {
        workers.submit(Callable { block() })
    } catch (error: RejectedExecutionException) {
        throw ProcessRunFailure(ExecutionFailureCode.SYSTEM_CLOSED, error)
    }

    private fun <T> await(future: Future<T>, process: ActiveProcess): T {
        while (true) {
            process.throwIfAborted()
            val remaining = process.deadlineNanos - System.nanoTime()
            if (remaining <= 0) {
                process.abort(ExecutionFailureCode.TIMEOUT)
                throw ProcessRunFailure(checkNotNull(process.abortCode))
            }
            try {
                return future.get(minOf(remaining, POLL_NANOS), TimeUnit.NANOSECONDS)
            } catch (error: TimeoutException) {
                // Poll the owner's terminal signal, not a second execution deadline.
                continue
            } catch (error: ExecutionException) {
                val code = process.abortCode ?: failureCode(error.cause)
                val exit = (error.cause as? ProcessRunFailure)?.exitCode
                throw ProcessRunFailure(code, error.cause, exit)
            } catch (error: CancellationException) {
                throw ProcessRunFailure(process.abortCode ?: ExecutionFailureCode.SYSTEM_CLOSED, error)
            }
        }
    }

    private fun failureCode(cause: Throwable?): ExecutionFailureCode = when (cause) {
        is android.os.RemoteException -> ExecutionFailureCode.BINDER_DEAD
        is SecurityException -> ExecutionFailureCode.PERMISSION_REVOKED
        is ProcessRunFailure -> cause.code
        else -> ExecutionFailureCode.IO_FAILED
    }

    private fun drainOwned(process: ActiveProcess, stream: InputStream): DrainResult = try {
        drain(stream)
    } catch (failure: IOException) {
        process.abort(ExecutionFailureCode.IO_FAILED)
        throw failure
    }

    private fun drain(stream: InputStream): DrainResult {
        val bytes = ByteArray(BUFFER_BYTES)
        val captured = ByteArrayOutputStream(minOf(maxCaptureBytes, BUFFER_BYTES))
        var retained = 0
        var truncated = false
        while (true) {
            val count = stream.read(bytes)
            if (count < 0) break
            val writable = minOf(count, maxCaptureBytes - retained)
            if (writable > 0) {
                captured.write(bytes, 0, writable)
                retained += writable
            }
            if (writable < count) truncated = true
        }
        return DrainResult(captured.toByteArray().toString(Charsets.UTF_8), truncated)
    }

    private data class DrainResult(val text: String, val truncated: Boolean)

    private companion object {
        const val BUFFER_BYTES = 8 * 1024
        val POLL_NANOS: Long = TimeUnit.MILLISECONDS.toNanos(25)
    }
}

package com.deepstudent.platform.execution

import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import moe.shizuku.server.IRemoteProcess
import moe.shizuku.server.IShizukuService

/**
 * Direct binder adapter for Shizuku 13.1.5.
 *
 * Verified AIDL contract from `dev.rikka.shizuku:aidl:13.1.5`:
 * `IShizukuService.newProcess(String[], String[], String): IRemoteProcess`,
 * `getUid(): int` (authorized probe only), `checkSelfPermission(): boolean`, and
 * `IRemoteProcess.getOutputStream/getInputStream/getErrorStream(): ParcelFileDescriptor`.
 *
 * `newProcess` is a normal (blocking) binder call that creates a process in the
 * Shizuku server; callers must invoke it off the main thread.
 */
internal class AndroidShizukuTransport(
    private val service: IShizukuService,
    private val log: ExecutionLogSink = AndroidExecutionLogSink,
) : ShizukuServiceTransport {
    override fun uid(): Int = log.atStage(ExecutionStage.UID_PROBE) { service.uid }

    override fun checkSelfPermission(): Boolean =
        log.atStage(ExecutionStage.PERMISSION_CHECK) { service.checkSelfPermission() }

    override fun newProcess(argv: Array<String>): RemoteProcessHandle {
        val process = log.atStage(ExecutionStage.PROCESS_START) {
            service.newProcess(argv, null, null) ?: throw IOException("Shizuku refused to create process")
        }
        return AndroidRemoteProcess.from(process, log)
    }

    override fun close() = Unit
}

/**
 * Owns the three descriptors returned by [IRemoteProcess].
 *
 * Contract details that matter for correctness:
 * - The returned `ParcelFileDescriptor`s are duped into this process; they must
 *   be closed here, not by the Shizuku server.
 * - `getErrorStream()` is not cached by Shizuku's `ShizukuRemoteProcess` and
 *   each call returns another descriptor for the same pipe. The adapter calls
 *   it exactly once.
 * - stdin is closed immediately. These commands are non-interactive; keeping
 *   the write end open makes a command that reads stdin wait for EOF forever.
 * - stdout and stderr are wrapped in auto-close streams so normal exit,
 *   timeout, cancellation and binder death all return to the descriptor
 *   baseline.
 */
internal class AndroidRemoteProcess private constructor(
    private val process: IRemoteProcess,
    private val output: InputStream,
    private val error: InputStream,
    private val log: ExecutionLogSink,
) : RemoteProcessHandle {
    private val closed = AtomicBoolean(false)

    override fun stdout(): InputStream = output

    override fun stderr(): InputStream = error

    override fun waitFor(): Int {
        // A single blocking waitFor IPC cannot be interrupted by Future.cancel.
        // Bounded server waits release the worker after cancellation/close even
        // when a revoked/dead service rejects destroy(). No alternate exec path.
        while (!closed.get()) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            if (log.atStage(ExecutionStage.PROCESS_WAIT) {
                    process.waitForTimeout(WAIT_SLICE_MILLIS, TimeUnit.MILLISECONDS.name)
                }
            ) {
                return log.atStage(ExecutionStage.PROCESS_WAIT) { process.exitValue() }
            }
        }
        throw IOException("Shizuku process handle closed while waiting")
    }

    override fun destroy() {
        log.atStage(ExecutionStage.PROCESS_DESTROY) { process.destroy() }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        closeQuietly(output, log)
        closeQuietly(error, log)
    }

    companion object {
        private const val WAIT_SLICE_MILLIS = 100L

        fun from(
            process: IRemoteProcess,
            log: ExecutionLogSink = AndroidExecutionLogSink,
        ): AndroidRemoteProcess {
            closeStdin(process, log)

            val stdoutDescriptor = try {
                log.atStage(ExecutionStage.PROCESS_STDOUT) {
                    process.inputStream ?: throw IOException("Shizuku process has no stdout descriptor")
                }
            } catch (failure: Exception) {
                destroyQuietly(process, log)
                throw failure
            }
            val stdout = ParcelFileDescriptor.AutoCloseInputStream(stdoutDescriptor)

            val stderrDescriptor = try {
                log.atStage(ExecutionStage.PROCESS_STDERR) {
                    process.errorStream ?: throw IOException("Shizuku process has no stderr descriptor")
                }
            } catch (failure: Exception) {
                closeQuietly(stdout, log)
                destroyQuietly(process, log)
                throw failure
            }
            val stderr = ParcelFileDescriptor.AutoCloseInputStream(stderrDescriptor)

            return AndroidRemoteProcess(process, stdout, stderr, log)
        }

        private fun closeStdin(process: IRemoteProcess, log: ExecutionLogSink) {
            val stdin = try {
                log.atStage(ExecutionStage.PROCESS_STDIN) { process.outputStream }
            } catch (failure: Exception) {
                destroyQuietly(process, log)
                throw failure
            }
            try {
                log.atStage(ExecutionStage.PROCESS_STDIN) {
                    stdin?.close() ?: throw IOException("Shizuku process has no stdin descriptor")
                }
            } catch (failure: Exception) {
                destroyQuietly(process, log)
                throw failure
            }
        }

        private fun destroyQuietly(process: IRemoteProcess, log: ExecutionLogSink) {
            runCatching { process.destroy() }.onFailure { log.recordFailure(ExecutionStage.PROCESS_DESTROY, it) }
        }

        private fun closeQuietly(stream: Closeable?, log: ExecutionLogSink) {
            runCatching { stream?.close() }.onFailure { log.recordFailure(ExecutionStage.PROCESS_CLOSE, it) }
        }
    }
}

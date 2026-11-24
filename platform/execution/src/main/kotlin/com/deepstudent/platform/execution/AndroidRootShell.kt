package com.deepstudent.platform.execution

import com.deepstudent.services.execution.api.ExecutionFailureCode
import com.deepstudent.services.execution.api.PreparedShell
import com.deepstudent.services.execution.api.ShellAdapter
import com.deepstudent.services.execution.api.ShellBudget
import com.deepstudent.services.execution.api.ShellCommand
import com.deepstudent.services.execution.api.ShellResult
import com.deepstudent.services.execution.api.ShellScope
import com.deepstudent.services.execution.api.ShellTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Operit exec-mode adaptation: one OS-managed su request, no shared root shell or fallback. */
class AndroidRootShell : ShellAdapter {
    override suspend fun prepare(command: ShellCommand): PreparedShell? {
        require(command.target == ShellTarget.ROOT)
        val executable = SU_PATHS.firstOrNull { File(it).canExecute() } ?: return null
        return RootCall(executable, command.text)
    }

    private class RootCall(private val executable: String, private val command: String) : PreparedShell {
        override val scope = ShellScope(ShellTarget.ROOT, 0, "root.uid0", "/")

        override suspend fun run(taskId: String): ShellResult {
            val started = System.nanoTime()
            val log = AndroidExecutionLogSink
            val owner = ActiveProcess(ShellBudget.DURATION_MILLIS, log, executionCorrelation(taskId))
            val argv = ShellProtocol.androidArgv(command, scope.uid, scope.directory, owned = true)
            val invocation = "exec " + argv.joinToString(" ", transform = ShellProtocol::quote)
            ShizukuProcessRunner(ShellBudget.STREAM_BYTES).use { runner ->
                var exited = false
                try {
                    runner.start(owner) { launch(executable, invocation) }
                    log.emit(ExecutionLogEvent("root_process_started", correlationId = executionCorrelation(taskId)))
                    val result = runner.run(owner)
                    exited = true
                    return failureOrResult(result, started)
                } catch (cancelled: CancellationException) {
                    owner.abort(ExecutionFailureCode.CANCELLED)
                    throw cancelled
                } catch (failure: ProcessRunFailure) {
                    owner.abort(failure.code)
                    return ShellResult.Unavailable(failure.code)
                } finally {
                    withContext(NonCancellable + Dispatchers.IO) {
                        if (exited) owner.closeOnce() else owner.destroyAndClose()
                    }
                }
            }
        }

        private fun failureOrResult(result: ProcessRun, started: Long): ShellResult {
            if (ShellProtocol.observedUid(result.output.stdout) == null) {
                return ShellResult.Unavailable(ExecutionFailureCode.PERMISSION_REQUIRED)
            }
            return ShellProtocol.result(scope.uid, result.exitCode, result.output, started)
        }
    }

    private companion object {
        val SU_PATHS = listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/debug_ramdisk/su")

        fun launch(executable: String, command: String): RemoteProcessHandle = try {
            LocalSuProcess(ProcessBuilder(executable, "-c", command).directory(File("/")).start())
        } catch (failure: IOException) {
            throw ProcessRunFailure(ExecutionFailureCode.PROCESS_START_FAILED, failure)
        }
    }
}

private class LocalSuProcess(private val process: Process) : RemoteProcessHandle {
    override fun stdout() = process.inputStream
    override fun stderr() = process.errorStream
    override fun waitFor() = process.waitFor()

    override fun destroy() {
        // Closing only this invocation's control pipe lets its privileged owner end the session.
        runCatching { process.outputStream.close() }
        if (!process.waitFor(750, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            process.waitFor(750, TimeUnit.MILLISECONDS)
        }
    }

    override fun close() {
        listOf(process.outputStream, process.inputStream, process.errorStream).forEach { runCatching { it.close() } }
    }
}

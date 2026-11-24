package com.deepstudent.app.execution

import android.os.Process
import com.deepstudent.engines.terminal.OperitPtyProcess
import com.deepstudent.platform.execution.ShellProtocol
import com.deepstudent.services.execution.api.ExecutionFailureCode
import com.deepstudent.services.execution.api.ExecutionOutput
import com.deepstudent.services.execution.api.PreparedShell
import com.deepstudent.services.execution.api.ShellAdapter
import com.deepstudent.services.execution.api.ShellBudget
import com.deepstudent.services.execution.api.ShellCommand
import com.deepstudent.services.execution.api.ShellResult
import com.deepstudent.services.execution.api.ShellScope
import com.deepstudent.services.execution.api.ShellTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

internal class AppPtyShell(private val filesDir: File) : ShellAdapter {
    override suspend fun prepare(command: ShellCommand): PreparedShell {
        require(command.target == ShellTarget.APP)
        val work = File(filesDir.canonicalFile, "terminal")
        check(work.canonicalFile == work && (work.isDirectory || work.mkdir())) { "terminal directory unavailable" }
        return Call(work, command.text)
    }

    private class Call(private val directory: File, private val script: String) : PreparedShell {
        override val scope = ShellScope(ShellTarget.APP, Process.myUid(), "app.terminal", "应用私有终端目录")

        override suspend fun run(taskId: String): ShellResult = withContext(Dispatchers.IO) {
            if (directory.canonicalFile != directory || !directory.isDirectory) {
                return@withContext ShellResult.Unavailable(ExecutionFailureCode.INVALID_REQUEST)
            }
            val start = System.nanoTime()
            val args = ShellProtocol.androidArgv(script, scope.uid, directory.path, owned = false)
            val pty = OperitPtyProcess.startCommand(directory, args)
            coroutineScope {
                try {
                    val output = async { capture(pty.input) }
                    val exit = runInterruptible { pty.awaitExit() }
                    ShellProtocol.result(scope.uid, exit, output.await(), start)
                } finally {
                    withContext(NonCancellable) {
                        try {
                            pty.interrupt()
                            pty.terminate()
                            pty.awaitExit()
                        } finally {
                            pty.close()
                        }
                    }
                }
            }
        }
    }
}

private fun capture(input: InputStream): ExecutionOutput {
    val bytes = ByteArray(8192)
    val captured = ByteArrayOutputStream()
    var truncated = false
    while (true) {
        val count = input.read(bytes)
        if (count < 0) break
        val retain = minOf(count, ShellBudget.STREAM_BYTES - captured.size())
        captured.write(bytes, 0, retain)
        if (retain != count) truncated = true
    }
    return ExecutionOutput(captured.toString(Charsets.UTF_8.name()), "", truncated, false)
}

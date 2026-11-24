package com.deepstudent.app.execution

import android.os.Process
import com.deepstudent.core.common.id.Digests
import com.deepstudent.features.environments.data.EnvironmentConsole
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

internal class LinuxShell(private val open: suspend () -> EnvironmentConsole?) : ShellAdapter {
    override suspend fun prepare(command: ShellCommand): PreparedShell? {
        require(command.target == ShellTarget.LINUX)
        val console = open() ?: return null
        val generation = console.activeGeneration() ?: return null
        val key = Digests.sha256HexUtf8(generation)
        val scope = ShellScope(ShellTarget.LINUX, 0, "linux.$key", "/root", Process.myUid())
        return Call(console, generation, scope, command.text)
    }

    private class Call(
        private val console: EnvironmentConsole,
        private val generation: String,
        override val scope: ShellScope,
        private val command: String,
    ) : PreparedShell {
        override suspend fun run(taskId: String): ShellResult {
            val started = System.nanoTime()
            val script = ShellProtocol.linuxScript(command, scope.uid, scope.directory)
            val run = console.run(generation, script, ShellBudget.DURATION_MILLIS, ShellBudget.STREAM_BYTES)
                ?: return ShellResult.Unavailable(ExecutionFailureCode.INVALID_REQUEST)
            return ShellProtocol.result(scope.uid, run.exitCode, ExecutionOutput(run.output, "", run.truncated, false), started)
        }
    }
}

package com.deepstudent.platform.execution

import com.deepstudent.services.execution.api.ExecutionAudit
import com.deepstudent.services.execution.api.ExecutionCommand
import com.deepstudent.services.execution.api.ExecutionRequest
import com.deepstudent.services.execution.api.ShellBudget

/** Never put approved script argv in diagnostics or returned audit records. */
internal data class ShizukuInvocation(
    val taskId: String,
    val callerId: String,
    val commandId: String,
    val argv: List<String>,
    val probe: Probe,
) {
    enum class Probe { NONE, FIXED_IDENTITY, APPROVED_SHELL }

    val owned: Boolean get() = probe == Probe.APPROVED_SHELL

    fun audit() = ExecutionAudit(taskId, callerId, commandId, if (owned) emptyList() else argv)

    fun captureLimit(default: Int): Int = if (owned) minOf(default, ShellBudget.STREAM_BYTES) else default

    companion object {
        fun diagnostic(request: ExecutionRequest) = ShizukuInvocation(
            request.taskId,
            request.callerId,
            request.command.commandId,
            request.command.argv,
            if (request.command === ExecutionCommand.Identity) Probe.FIXED_IDENTITY else Probe.NONE,
        )

        fun approved(taskId: String, script: String, uid: Int, directory: String) = ShizukuInvocation(
            taskId,
            "workspace.console",
            "shell.approved",
            ShellProtocol.androidArgv(script, uid, directory, owned = true),
            Probe.APPROVED_SHELL,
        )
    }
}

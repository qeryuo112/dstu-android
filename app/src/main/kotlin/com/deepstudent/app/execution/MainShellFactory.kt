package com.deepstudent.app.execution

import android.content.Context
import com.deepstudent.features.environments.data.EnvironmentConsole
import com.deepstudent.platform.execution.AndroidRootShell
import com.deepstudent.services.execution.api.ExecutionSystem
import com.deepstudent.services.execution.api.ShellAdapter
import com.deepstudent.services.execution.api.ShellTarget

internal fun mainShellRouter(
    context: Context,
    linux: suspend () -> EnvironmentConsole?,
    shizuku: suspend () -> ExecutionSystem,
): ShellRouter = ShellRouter(
    mapOf(
        ShellTarget.APP to AppPtyShell(context.filesDir),
        ShellTarget.LINUX to LinuxShell(linux),
        ShellTarget.SHIZUKU to ShellAdapter { (shizuku() as? ShellAdapter)?.prepare(it) },
        ShellTarget.ROOT to AndroidRootShell(),
    ),
)

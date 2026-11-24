package com.deepstudent.platform.execution

import com.deepstudent.services.execution.api.ExecutionFailureCode
import com.deepstudent.services.execution.api.ExecutionOutput
import com.deepstudent.services.execution.api.ShellReceipt
import com.deepstudent.services.execution.api.ShellResult
import java.util.concurrent.TimeUnit

/** Fixed bootstrap checks the same child identity and directory before interpreting approved text. */
object ShellProtocol {
    private const val MARKER = "DSTU_SHELL_UID:"

    fun androidArgv(command: String, uid: Int, cwd: String, owned: Boolean): List<String> {
        val shell = "/system/bin/sh"
        val run = "/system/bin/env -i PATH=/system/bin:/system/xbin HOME=${quote(cwd)} LANG=C.UTF-8 $shell -c \"\$1\""
        val bootstrap = checkedPrefix("/system/bin/id", uid, cwd) +
            if (owned) ownedScript(run) else "exec $run </dev/null"
        val argv = listOf(shell, "-c", bootstrap, "dstu-approved-shell", command)
        return if (owned) listOf("/system/bin/setsid") + argv else argv
    }

    fun linuxScript(command: String, uid: Int, cwd: String): String =
        checkedPrefix("/usr/bin/id", uid, cwd) + "exec /bin/bash --noprofile --norc -c ${quote(command)}"

    fun quote(text: String): String = "'" + text.replace("'", "'\"'\"'") + "'"

    fun observedUid(output: String): Int? = output.substringBefore('\n').trimEnd('\r')
        .takeIf { it.startsWith(MARKER) }?.removePrefix(MARKER)?.toIntOrNull()

    fun result(uid: Int, exit: Int, output: ExecutionOutput, startedNanos: Long): ShellResult {
        val observed = observedUid(output.stdout)
        if (observed != uid) return ShellResult.Unavailable(ExecutionFailureCode.UID_MISMATCH)
        val body = output.copy(stdout = output.stdout.substringAfter('\n', ""))
        val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos)
        return ShellResult.Exited(ShellReceipt(observed, exit, body, elapsed))
    }

    private fun checkedPrefix(id: String, uid: Int, cwd: String): String = """
        dstu_uid=${'$'}($id -u) || exit 125
        [ "${'$'}dstu_uid" = "$uid" ] || exit 126
        cd ${quote(cwd)} || exit 125
        printf '$MARKER%s\n' "${'$'}dstu_uid"
    """.trimIndent() + "\n"

    // The control descriptor never becomes command stdin. EOF signals the already-owned
    // session; the watcher uses only its own session leader, never a caller-provided PID.
    private fun ownedScript(run: String) = """
        exec 3<&0
        $run </dev/null 3<&- &
        dstu_child=${'$'}!
        (
            trap '' TERM
            IFS= read -r dstu_signal <&3
            /system/bin/kill -TERM -- "-${'$'}${'$'}"
            /system/bin/sleep 0.2
            /system/bin/kill -KILL -- "-${'$'}${'$'}"
        ) &
        dstu_watch=${'$'}!
        exec 3<&-
        wait "${'$'}dstu_child"
        dstu_exit=${'$'}?
        /system/bin/kill -KILL "${'$'}dstu_watch" 2>/dev/null
        wait "${'$'}dstu_watch" 2>/dev/null
        exit "${'$'}dstu_exit"
    """.trimIndent()
}

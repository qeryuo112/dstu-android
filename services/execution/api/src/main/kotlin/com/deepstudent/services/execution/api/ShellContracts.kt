package com.deepstudent.services.execution.api

import kotlinx.coroutines.flow.StateFlow

/** Explicit target, never an ordered fallback list. */
enum class ShellTarget { APP, LINUX, SHIZUKU, ROOT }

data class ShellCommand(val target: ShellTarget, val text: String) {
    init {
        require(text.isNotBlank() && text.toByteArray(Charsets.UTF_8).size <= 8192) { "invalid command length" }
        require(text.none { (it.isISOControl() && it != '\n' && it != '\t') || Character.getType(it) == Character.FORMAT.toInt() }) {
            "command contains hidden controls"
        }
    }
}

/** [uid] is effective UID; Linux uses the guest UID, with the Android app as the tracer owner. */
data class ShellScope(val target: ShellTarget, val uid: Int, val resource: String, val directory: String, val ownerUid: Int? = null)

object ShellBudget {
    const val DURATION_MILLIS = 20_000L
    const val STREAM_BYTES = 64 * 1024
    const val TOTAL_BYTES = 2 * STREAM_BYTES
}

data class ShellReceipt(val observedUid: Int, val exitCode: Int, val output: ExecutionOutput, val durationMillis: Long)

sealed interface ShellResult {
    data class Exited(val receipt: ShellReceipt) : ShellResult
    data class Unavailable(val code: ExecutionFailureCode) : ShellResult
    data class Blocked(val reason: String) : ShellResult
}

/** Trusted platform prepared a fixed scope and an immutable command; preparing never spawns it. */
interface PreparedShell {
    val scope: ShellScope
    suspend fun run(taskId: String): ShellResult
}

fun interface ShellAdapter {
    suspend fun prepare(command: ShellCommand): PreparedShell?
}

data class ShellRunState(
    val taskId: String? = null,
    val waitingOrRunning: Boolean = false,
    val scope: ShellScope? = null,
    val result: ShellResult? = null,
)

/** App-owned, retains current invocation across Activity recreation, no history of command output. */
interface ShellConsole {
    val state: StateFlow<ShellRunState>
    suspend fun submit(command: ShellCommand)
    suspend fun revoke(taskId: String)
}

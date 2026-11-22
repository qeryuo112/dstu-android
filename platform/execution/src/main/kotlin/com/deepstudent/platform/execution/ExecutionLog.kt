package com.deepstudent.platform.execution

import android.util.Log
import java.security.MessageDigest

internal sealed interface ExecutionLogRecord

internal data class ExecutionLogEvent(
    val kind: String,
    val state: String? = null,
    val backendUid: Int? = null,
    val correlationId: String? = null,
    val observedUid: Int? = null,
    val commandId: String? = null,
    val exitCode: Int? = null,
    val durationMillis: Long? = null,
) : ExecutionLogRecord

internal fun interface ExecutionLogSink {
    fun emit(event: ExecutionLogRecord)
}

/** Raw task/caller IDs may themselves contain secrets; only a digest reaches logs. */
internal fun executionCorrelation(taskId: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(taskId.toByteArray(Charsets.UTF_8))
        .take(12)
        .joinToString("") { "%02x".format(it) }

internal fun ExecutionLogRecord.format(): String = when (this) {
    is ExecutionFailureLogEvent -> "owner=platform.execution event=boundary_failure stage=${stage.name} exception=$exceptionClass"
    is ExecutionLogEvent -> formatEvent()
}

private fun ExecutionLogEvent.formatEvent(): String = buildString {
    append("owner=platform.execution event=").append(kind)
    state?.let { append(" state=").append(it) }
    backendUid?.let { append(" backend_uid=").append(it) }
    observedUid?.let { append(" observed_uid=").append(it) }
    correlationId?.let { append(" correlation=").append(it) }
    commandId?.let { append(" command=").append(it) }
    exitCode?.let { append(" exit=").append(it) }
    durationMillis?.let { append(" duration_ms=").append(it) }
}

internal object AndroidExecutionLogSink : ExecutionLogSink {
    override fun emit(event: ExecutionLogRecord) {
        Log.i("DstuExecution", event.format())
    }
}

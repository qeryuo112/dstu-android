package com.deepstudent.app.storage

import android.util.Log
import com.deepstudent.app.diagnostics.S231Diagnostics
import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.core.common.logging.LogRecord
import com.deepstudent.core.common.logging.Logger

/** Diagnostic fields are explicitly selected; payloads and exception messages never enter logcat. */
internal object AndroidOperationLogger : Logger {
    private val allowed = setOf(
        "owner", "stage", "status", "state", "phase", "outcome", "durationMillis", "duration_ms", "elapsedMs",
        "generation", "change", "result", "purpose", "category", "reason", "capMs", "remainingMs", "database",
    )

    private val databaseNames = setOf("vfs", "chat_v2", "mistakes", "llm_usage")

    override fun log(record: LogRecord) {
        // Diagnostics must not change the outcome of a business operation.
        runCatching { S231Diagnostics.record(record) }
        val fields = allowed.mapNotNull { key ->
            when (val value = record.fields[key]) {
                is JsonValue.Text -> if (key != "database" || value.value in databaseNames) "$key=${value.value}" else null
                is JsonValue.Number -> if (key == "database") null else "$key=${value.literal}"
                else -> null
            }
        }
        Log.i(
            "DstuOperation",
            "event=${record.event} taskId=${record.taskId?.value} " +
                "correlationId=${record.correlationId?.value} ${fields.joinToString(" ")}",
        )
    }
}

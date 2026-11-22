package com.deepstudent.app.storage

import android.util.Log
import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.core.common.logging.LogRecord
import com.deepstudent.core.common.logging.Logger

/** Diagnostic fields are explicitly selected; payloads and exception messages never enter logcat. */
internal object AndroidOperationLogger : Logger {
    private val allowed = setOf("owner", "stage", "status", "state", "phase", "outcome", "durationMillis", "duration_ms", "elapsedMs")

    override fun log(record: LogRecord) {
        val fields = allowed.mapNotNull { key ->
            when (val value = record.fields[key]) {
                is JsonValue.Text -> "$key=${value.value}"
                is JsonValue.Number -> "$key=${value.literal}"
                else -> null
            }
        }
        Log.i("DstuOperation", "event=${record.event} taskId=${record.taskId?.value} " +
            "correlationId=${record.correlationId?.value} ${fields.joinToString(" ")}")
    }
}

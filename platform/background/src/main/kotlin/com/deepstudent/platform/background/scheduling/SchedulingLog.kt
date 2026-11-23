package com.deepstudent.platform.background.scheduling

import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.core.common.logging.LogLevel
import com.deepstudent.core.common.logging.LogRecord
import com.deepstudent.core.common.logging.Logger

/**
 * Fixed-field scheduling diagnostics. Reminder titles/bodies and any local-store content are never
 * copied into log records; schedule id and occurrence are opaque platform identities.
 */
internal class SchedulingLog(private val logger: Logger) {
    fun event(event: String, state: String, request: ScheduledWorkRequest? = null, fire: ReminderFire? = null, elapsedMs: Long? = null) {
        val fields = JsonValue.Object.Builder()
            .put("owner", "platform.background.scheduling")
            .put("state", state)
        request?.let {
            fields.put("schedule", it.scheduleId.value)
            fields.put("occurrence", it.occurrence.value)
        }
        fire?.let {
            fields.put("schedule", it.scheduleId.value)
            fields.put("occurrence", it.occurrence.value)
        }
        if (elapsedMs != null) fields.put("elapsedMs", elapsedMs)
        try {
            logger.log(LogRecord(LogLevel.INFO, event, fields = fields.build()))
        } catch (_: Exception) {
            // Diagnostics must never cancel work or block receiver completion.
        }
    }
}

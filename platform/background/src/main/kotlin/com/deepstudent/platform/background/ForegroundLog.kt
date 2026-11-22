package com.deepstudent.platform.background

import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.core.common.logging.LogLevel
import com.deepstudent.core.common.logging.LogRecord
import com.deepstudent.core.common.logging.Logger

internal class ForegroundLog(private val logger: Logger) {
    fun event(event: String, duty: ForegroundDuty? = null, state: String, elapsedMs: Long? = null) {
        val fields = JsonValue.Object.Builder()
            .put("owner", "platform.background")
            .put("state", state)
        if (elapsedMs != null) fields.put("elapsedMs", elapsedMs)
        try {
            logger.log(LogRecord(LogLevel.INFO, event, duty?.taskId, duty?.correlationId, fields.build()))
        } catch (_: Exception) {
            // Diagnostics must not prevent service cleanup or task cancellation.
        }
    }
}

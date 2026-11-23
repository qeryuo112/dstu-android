package com.deepstudent.platform.background.scheduling

import com.deepstudent.core.common.logging.LogRecord
import com.deepstudent.core.common.logging.Logger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SchedulingLogTest {
    @Test
    fun logsOnlyFixedIdentityFieldsNeverReminderContent() {
        val records = mutableListOf<LogRecord>()
        val logger = object : Logger {
            override fun log(record: LogRecord) {
                records += record
            }
        }
        val fire = ReminderFire(ScheduleId.parse("schedule-1"), OccurrenceNo.positive(9))
        SchedulingLog(logger).event(
            event = "background.reminder",
            state = "shown",
            fire = fire,
            elapsedMs = 12,
        )
        val record = records.single()
        assertEquals(setOf("owner", "state", "schedule", "occurrence", "elapsedMs"), record.fields.keys().toSet())
        assertEquals("platform.background.scheduling", record.fields.asMap()["owner"]?.literal()?.trim('"'))
        assertFalse(record.toString().contains("secret reminder title"))
        assertTrue(record.fields.keys().contains("schedule"))
    }

    @Test
    fun loggerFailureNeverEscapesSchedulingCode() {
        val failing = object : Logger {
            override fun log(record: LogRecord): Unit = throw IllegalStateException("sensitive logger failure")
        }
        SchedulingLog(failing).event("background.schedule.work", "done")
    }
}

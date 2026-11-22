package com.deepstudent.features.environments.backend

import java.util.logging.Handler
import java.util.logging.LogRecord
import java.util.logging.Logger
import org.junit.Assert.*
import org.junit.Test

class EnvironmentLogTest {
    @Test fun logsContainErrorTypeButNotExceptionPayloadOrForgedNewlines() {
        val records = mutableListOf<LogRecord>()
        val logger = Logger.getLogger("DstuEnvironments")
        val handler = object : Handler() {
            override fun publish(record: LogRecord) { records += record }
            override fun flush() = Unit
            override fun close() = Unit
        }
        logger.addHandler(handler)
        try {
            EnvironmentLog.failure("operation-failed", IllegalStateException("content://secret/password?token=private"), "task=safe\nforged-event")
            val record = records.single()
            assertFalse(record.message.contains("secret"))
            assertFalse(record.message.contains('\n'))
            assertTrue(record.message.contains("IllegalStateException"))
            assertNull(record.thrown)
        } finally { logger.removeHandler(handler) }
    }
}

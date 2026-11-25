package com.deepstudent.app.diagnostics

import com.deepstudent.core.common.id.TaskId
import com.deepstudent.core.common.logging.LogLevel
import com.deepstudent.core.common.logging.LogRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class S231HistoryTest {
    @Test
    fun privateHistorySurvivesReopenBoundsAndNeverStoresRawIdentifiers() {
        val dir = Files.createTempDirectory("s231-history").toFile()
        try {
            val history = S231History(dir)
            val first = S231DiagnosticBuffer()
            repeat(140) { first.log(LogRecord(LogLevel.INFO, "runtime.finished", taskId = TaskId.parse("SECRET_RAW_TASK_$it"))) }
            history.write(first.snapshot().entries)
            assertTrue(dir.resolve("s231-diagnostic-history.bin").length() <= S231History.MAX_BYTES)
            assertFalse(dir.resolve("s231-diagnostic-history.bin").readBytes().toString(Charsets.ISO_8859_1).contains("SECRET"))
            val old = history.read()
            assertEquals(128, old.size)
            val restored = S231DiagnosticBuffer(old)
            restored.log(LogRecord(LogLevel.INFO, "runtime.finished", taskId = TaskId.parse("SECRET_NEW_PROCESS")))
            assertEquals(128, restored.snapshot().entries.size)
            assertTrue(restored.snapshot().entries.last().task!! > old.last().task!!)
            history.write(restored.snapshot().entries)
            assertEquals(restored.snapshot().entries, history.read())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun corruptOrOversizedHistoryIsDiscardedWithoutReadingRawLogs() {
        val dir = Files.createTempDirectory("s231-corrupt").toFile()
        try {
            val file = dir.resolve("s231-diagnostic-history.bin")
            file.writeBytes("SECRET_NOT_A_HISTORY".toByteArray())
            assertTrue(S231History(dir).read().isEmpty())
            file.writeBytes(ByteArray(S231History.MAX_BYTES + 1))
            assertTrue(S231History(dir).read().isEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }
}

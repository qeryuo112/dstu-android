package com.deepstudent.app.diagnostics

import com.deepstudent.core.common.id.CorrelationId
import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.core.common.logging.LogLevel
import com.deepstudent.core.common.logging.LogRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Contract input from S2.30; its not-yet-accepted implementation is not linked into this snapshot. */
class S231ApplyContractTest {
    @Test
    fun fourOwnersAndConsumeCorrelateWithTransferWithoutExposingIds() {
        val sink = S231DiagnosticBuffer()
        val correlation = CorrelationId.parse("private-correlation-s230")
        sink.log(
            LogRecord(
                LogLevel.INFO,
                "sync.transfer.phase",
                correlationId = correlation,
                fields = JsonValue.Object.Builder().put("owner", "sync").put("phase", "DOWNLOAD").build(),
            ),
        )
        val databases = listOf("vfs", "chat_v2", "mistakes", "llm_usage")
        val statuses = listOf("COMMITTED", "REPLAYED", "FAILED", "CANCELLED")
        databases.zip(statuses).forEach { (database, status) ->
            val fields = JsonValue.Object.Builder().put("owner", "sync/apply").put("phase", "DOWNLOAD")
                .put("stage", "APPLY").put("database", database).put("status", status)
                .put("elapsedMs", 42).put("payload", "SECRET_CONTENT").build()
            sink.log(LogRecord(LogLevel.INFO, "sync.apply.owner", correlationId = correlation, fields = fields))
        }
        val consume = JsonValue.Object.Builder().put("owner", "sync/apply").put("stage", "CONSUME")
            .put("database", "vfs").build()
        sink.log(LogRecord(LogLevel.INFO, "sync.apply.consume", correlationId = correlation, fields = consume))
        val entries = sink.snapshot().entries
        assertEquals(6, entries.size)
        assertTrue(entries.all { it.correlation == entries.first().correlation && it.correlation != null })
        val owners = entries.subList(1, 5)
        assertEquals(S231Database.entries.toList(), owners.map { it.location.database })
        assertEquals(
            listOf(S231Status.COMMITTED, S231Status.REPLAYED, S231Status.FAILED, S231Status.CANCELLED),
            owners.map { it.progress.status },
        )
        assertTrue(owners.all { it.event == S231Event.SYNC_APPLY_OWNER && it.location.owner == S231Owner.SYNC_APPLY })
        assertTrue(owners.all { it.location.phase == S231Stage.DOWNLOAD && it.location.stage == S231Stage.APPLY })
        assertTrue(owners.all { it.progress.durationMillis == 42L })
        val last = entries.last()
        assertEquals(S231Event.SYNC_APPLY_CONSUME, last.event)
        assertEquals(S231Stage.CONSUME, last.location.stage)
        assertEquals(S231Database.VFS, last.location.database)
        val output = S231TextBundle.create(sink.snapshot()).toString(Charsets.UTF_8)
        assertTrue(output.contains("event=SYNC_APPLY_OWNER"))
        assertTrue(output.contains("stage=CONSUME"))
        assertFalse(output.contains("private-correlation-s230"))
        assertFalse(output.contains("SECRET_CONTENT"))
    }

    @Test
    fun hostileOrUnknownDatabaseAndStatusAreDroppedNotEchoed() {
        val sink = S231DiagnosticBuffer()
        val inputs = listOf("vfs/SECRET", "VFS", "chat_v2\nSECRET", "SECRET_TOKEN")
        for (value in inputs) {
            val fields = JsonValue.Object.Builder().put("owner", "sync/apply").put("stage", "APPLY")
                .put("database", value).put("status", value).build()
            sink.log(LogRecord(LogLevel.INFO, "sync.apply.owner", fields = fields))
        }
        val entries = sink.snapshot().entries
        assertTrue(entries.all { it.location.database == null && it.progress.status == null })
        assertNull(entries.first().error)
        val output = S231TextBundle.create(sink.snapshot()).toString(Charsets.UTF_8)
        inputs.forEach { assertFalse(output.contains(it)) }
    }
}

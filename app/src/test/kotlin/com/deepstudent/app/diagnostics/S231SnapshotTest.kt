package com.deepstudent.app.diagnostics

import com.deepstudent.core.common.error.DstuError
import com.deepstudent.core.common.error.DstuErrorKind
import com.deepstudent.core.common.id.CorrelationId
import com.deepstudent.core.common.id.TaskId
import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.core.common.logging.LogLevel
import com.deepstudent.core.common.logging.LogRecord
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

internal class S231SnapshotTest {
    private fun capture(vararg records: LogRecord): S231Snapshot =
        (S231Snapshot.capture(records.toList()) as S231CaptureResult.Ready).snapshot

    private fun record(
        fields: JsonValue.Object = JsonValue.Object.EMPTY,
        error: DstuError? = null,
        task: String? = null,
        correlation: String? = null,
        event: String = "sync.transfer.finished",
    ): LogRecord = LogRecord(
        level = LogLevel.ERROR,
        event = event,
        taskId = task?.let(TaskId::parse),
        correlationId = correlation?.let(CorrelationId::parse),
        fields = fields,
        error = error,
    )

    @Test
    fun secretCanariesNeverCrossAnyInputBoundary() {
        val secret = "S231_SECRET_CANARY_7931"
        val taskSecret = "S231_TASK_CANARY_6821"
        val correlationSecret = "S231_CORRELATION_CANARY_2357"
        val path = "/home/alice/private/db-user.sqlite"
        val url = "https://user:password@example.test/file?token=private-query-canary"
        val body = "用户正文金丝雀：不要导出这段内容"
        val privateKey = "-----BEGIN PRIVATE KEY-----\nprivate-key-canary\n-----END PRIVATE KEY-----"
        val fields = JsonValue.Object.Builder()
            .put("owner", "sync-$secret")
            .put("database", path)
            .put("stage", "DOWNLOAD\n$secret")
            .put("phase", url)
            .put("status", secret)
            .put("state", secret)
            .put("outcome", secret)
            .put("durationMillis", secret)
            .put("correlationId", correlationSecret)
            .put("error", secret)
            .put("body", body)
            .put("credential", privateKey)
            .put(secret, secret)
            .put("nested", JsonValue.Object.Builder().put("prompt", body).put("token", secret).build())
            .build()
        val sourceError = DstuError(secret, DstuErrorKind.UNAUTHORIZED, "$secret $path $body", mapOf("key" to privateKey))
        val source = record(fields, sourceError, taskSecret, event = "$secret $url")
        val snapshot = capture(source)
        val entry = snapshot.entries.single()
        assertEquals(1, entry.task)
        assertEquals(1, entry.correlation)
        assertEquals(S231ErrorCode.OTHER, entry.error)
        assertNull(entry.location.owner)
        assertNull(entry.location.database)
        assertNull(entry.location.stage)
        assertNull(entry.location.phase)
        assertNull(entry.progress.status)
        assertNull(entry.progress.state)
        assertNull(entry.progress.outcome)
        assertNull(entry.progress.durationMillis)

        val output = S231TextBundle.create(snapshot).toString(Charsets.UTF_8)
        val projection = snapshot.entries.toString()
        listOf(secret, taskSecret, correlationSecret, path, url, body, privateKey, "private-query-canary")
            .forEach { canary ->
                assertFalse("bundle contains a secret", output.contains(canary))
                assertFalse("snapshot contains a secret", projection.contains(canary))
            }
        assertTrue(output.contains("task=T1 correlation=C1"))
        assertTrue(output.contains("error=OTHER"))
    }

    @Test
    fun knownValuesAndAliasesProvideTaskDatabaseAndStageWithoutRawIds() {
        val fields = JsonValue.Object.Builder().put("owner", "sync").put("database", "vfs")
            .put("stage", "DOWNLOAD").put("phase", "FAILED").put("status", "failed")
            .put("state", "running").put("outcome", "BLOCKED").put("elapsedMs", 123)
            .put("correlationId", "correlation-private-1").put("error", "NETWORK").build()
        val first = record(fields, task = "task-private-1")
        val second = record(fields, task = "task-private-1", correlation = "correlation-private-1")
        val third = record(fields, task = "task-private-2", correlation = "correlation-private-2")
        val snapshot = capture(first, second, third)
        val entry = snapshot.entries.first()
        assertEquals(S231Owner.SYNC, entry.location.owner)
        assertEquals(S231Database.VFS, entry.location.database)
        assertEquals(S231Stage.DOWNLOAD, entry.location.stage)
        assertEquals(S231Stage.FAILED, entry.location.phase)
        assertEquals(S231Status.FAILED, entry.progress.status)
        assertEquals(S231State.RUNNING, entry.progress.state)
        assertEquals(S231Outcome.BLOCKED, entry.progress.outcome)
        assertEquals(123L, entry.progress.durationMillis)
        assertEquals(S231ErrorCode.NETWORK, entry.error)
        assertEquals(listOf(1, 1, 2), snapshot.entries.map { it.task })
        assertEquals(listOf(1, 1, 2), snapshot.entries.map { it.correlation })
        val text = S231TextBundle.create(snapshot).toString(Charsets.UTF_8)
        assertTrue(text.contains("database=VFS stage=DOWNLOAD"))
        assertTrue(text.contains("task=T2 correlation=C2"))
        assertFalse(text.contains("task-private"))
        assertFalse(text.contains("correlation-private"))
    }

    @Test
    fun durationsAndUntrustedValuesAreStrictlyBounded() {
        fun projected(value: JsonValue): Long? = capture(
            record(
                JsonValue.Object.Builder()
                    .put("durationMillis", value).build(),
            ),
        ).entries.single().progress.durationMillis
        assertEquals(0L, projected(JsonValue.Number.integer(0)))
        assertEquals(86_400_000L, projected(JsonValue.Number.integer(86_400_000)))
        assertNull(projected(JsonValue.Number.integer(-1)))
        assertNull(projected(JsonValue.Number.integer(86_400_001)))
        assertNull(projected(JsonValue.Number.raw("1.5")))
        assertNull(projected(JsonValue.Number.raw("1e6")))
        assertNull(projected(JsonValue.Text("123")))
        assertNull(projected(JsonValue.Number.raw("9".repeat(4096))))
        val legacy = JsonValue.Object.Builder().put("owner", "runtime.recovery")
            .put("phase", "CLAIM").put("outcome", "APPLIED").put("duration_ms", 42).build()
        val entry = capture(record(legacy)).entries.single()
        assertEquals(S231Owner.RUNTIME_RECOVERY, entry.location.owner)
        assertEquals(S231Stage.CLAIM, entry.location.phase)
        assertEquals(S231Outcome.APPLIED, entry.progress.outcome)
        assertEquals(42L, entry.progress.durationMillis)
    }

    @Test
    fun catalogueDoesNotTrustEvenRecognisedSourceErrorMessagesOrKinds() {
        val groups = mapOf(
            ("sync.transfer.finished" to "sync") to setOf(
                "UNEXPECTED", "CANCELLED", "NETWORK", "STORAGE", "AUTHENTICATION",
                "INVALID_REPOSITORY", "ENCRYPTED_DATA_INVALID", "CREDENTIAL_UNAVAILABLE", "LIMIT_EXCEEDED",
            ),
            ("runtime.finished" to "runtime") to setOf("TASK_FAILED", "UNEXPECTED", "CANCELLED"),
            ("notes.operation" to "features/notes") to setOf("INVALID_NOTE_TITLE", "INVALID_NOTE_TAGS", "INVALID_NOTE_ID"),
            ("resources.operation" to "services/resources") to setOf(
                "RESOURCE_INSUFFICIENT_SPACE",
                "INVALID_ATTACHMENT",
                "INVALID_ATTACHMENT_SIZE",
                "ATTACHMENT_CORRUPT",
            ),
            ("backup.restore" to "services.backup.restore") to setOf("INVALID_PLAN", "UNSAFE_ARCHIVE", "HASH_MISMATCH", "IO_FAILURE"),
            ("security.crypto" to "platform/security") to setOf("HEADER_INVALID", "AUTHENTICATION_FAILED"),
            ("database.migration" to "platform/database") to setOf("MIGRATION_FAILED"),
            ("database.operation" to "platform/database") to setOf("DATABASE_OPERATION_FAILED", "DATABASE_TIMED_OUT"),
        )
        for ((source, codes) in groups) {
            for (name in codes) {
                val code = S231ErrorCode.valueOf(name)
                val unsafe = DstuError(name, DstuErrorKind.UNAUTHORIZED, "SECRET_ERROR_MESSAGE", mapOf("secret" to "SECRET_DETAIL"))
                val fields = JsonValue.Object.Builder().put("owner", source.second).build()
                val snapshot = capture(record(fields, error = unsafe, event = source.first))
                assertEquals(code, snapshot.entries.single().error)
                assertTrue(code.asDstuError().details.isEmpty())
                val text = S231TextBundle.create(snapshot).toString(Charsets.UTF_8)
                assertTrue(text.contains("error=${code.name}"))
                assertFalse(text.contains("SECRET_ERROR_MESSAGE"))
                assertFalse(text.contains("SECRET_DETAIL"))
            }
        }
        val unknown = DstuError.internal("CREDENTIAL_IN_RAW_CODE", "secret")
        assertEquals(S231ErrorCode.OTHER, capture(record(error = unknown)).entries.single().error)
        assertNull(S231ErrorCode.from(null))
    }

    @Test
    fun outputIsDeterministicBoundedAndImmutable() {
        val fields = JsonValue.Object.Builder().put("owner", "sync.configuration").put("database", "llm_usage")
            .put("stage", "ACKNOWLEDGE").put("phase", "ACKNOWLEDGE").put("status", "timed_out")
            .put("state", "waiting_approval").put("outcome", "SKIPPED").put("durationMillis", 86_400_000)
            .put("error", "ENCRYPTED_DATA_INVALID").build()
        val records = (1..S231Snapshot.MAX_RECORDS).map { number ->
            record(fields, task = "private-task-$number", correlation = "private-correlation-$number")
        }
        val snapshot = (S231Snapshot.capture(records) as S231CaptureResult.Ready).snapshot
        val a = S231TextBundle.create(snapshot)
        val b = S231TextBundle.create((S231Snapshot.capture(records) as S231CaptureResult.Ready).snapshot)
        assertArrayEquals(a, b)
        assertTrue(a.size <= S231TextBundle.MAX_BYTES)
        assertTrue(a.toString(Charsets.UTF_8).contains("records=128"))
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (snapshot.entries as MutableList<S231Entry>).clear()
        }
        assertEquals(S231Snapshot.MAX_RECORDS, snapshot.entries.size)
    }

    @Test
    fun overLimitIsRefusedBeforeAnyRecordIsRead() {
        val overLimit = object : AbstractList<LogRecord>() {
            override val size: Int = S231Snapshot.MAX_RECORDS + 1
            override fun get(index: Int): LogRecord = error("must not access oversized input")
        }
        val result = S231Snapshot.capture(overLimit) as S231CaptureResult.Refused
        assertEquals("S231_LIMIT_EXCEEDED", result.error.code)
        assertEquals(DstuErrorKind.VALIDATION, result.error.kind)
        assertTrue(result.error.details.isEmpty())
    }

    @Test
    fun unknownCorrelationValuesAreOnlyAliasedAndLengthLimited() {
        val huge = "correlation-secret".repeat(100)
        val unknown = JsonValue.Object.Builder().put("correlationId", huge).build()
        val empty = JsonValue.Object.Builder().put("correlationId", "").build()
        assertNull(capture(record(unknown)).entries.single().correlation)
        assertNull(capture(record(empty)).entries.single().correlation)
        val unsafe = "secret@host/path?token=hidden"
        val acceptableLength = JsonValue.Object.Builder().put("correlationId", unsafe).build()
        val bytes = S231TextBundle.create(capture(record(acceptableLength))).toString(Charsets.UTF_8)
        assertTrue(bytes.contains("correlation=C1"))
        assertFalse(bytes.contains(unsafe))
    }
}

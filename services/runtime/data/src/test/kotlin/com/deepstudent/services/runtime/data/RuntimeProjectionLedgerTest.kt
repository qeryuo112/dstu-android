package com.deepstudent.services.runtime.data

import com.deepstudent.core.common.id.TaskId
import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.core.common.logging.LogRecord
import com.deepstudent.core.common.logging.Logger
import com.deepstudent.platform.database.DatabaseFactory
import com.deepstudent.services.runtime.api.RuntimeApprovalState
import com.deepstudent.services.runtime.api.RuntimeAuditAction
import com.deepstudent.services.runtime.api.RuntimeProjectionChange
import com.deepstudent.services.runtime.api.RuntimeProjectionResult
import com.deepstudent.services.runtime.api.RuntimeRecordKey
import com.deepstudent.services.runtime.api.RuntimeState
import com.deepstudent.services.runtime.api.RuntimeTaskGeneration
import com.deepstudent.services.runtime.api.TaskCursor
import com.deepstudent.services.runtime.api.TaskKind
import com.deepstudent.services.runtime.api.TaskSubmission
import com.deepstudent.services.runtime.backend.LedgerCommand
import com.deepstudent.services.runtime.backend.LedgerResult
import com.deepstudent.services.runtime.backend.leaseToken
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.time.Instant
import java.util.Collections

class RuntimeProjectionLedgerTest {
    private val now = Instant.parse("2026-11-17T00:00:00Z")
    private val kind = TaskKind.parse("test.task")

    @Test
    fun projectionsSurviveReopenAndPaginationIsStable() = runBlocking {
        val dir = Files.createTempDirectory("runtime-projection").toFile()
        val file = dir.resolve("runtime.db")
        val taskId = TaskId.parse("projection-task")
        val generation = RuntimeTaskGeneration(taskId, 1)
        try {
            DatabaseFactory.openLocal(file).use { database ->
                val ledger = SqlTaskLedger(database)
                ledger.create(taskId, TaskSubmission(kind), now)
                val start = requireNotNull(ledger.snapshot(taskId)).cursor
                repeat(3) { index ->
                    assertEquals(
                        RuntimeProjectionResult.APPLIED,
                        ledger.apply(generation, RuntimeProjectionChange.RequestApproval(key("req-$index"), key("op")), now),
                    )
                }
                assertEquals(
                    RuntimeProjectionResult.APPLIED,
                    ledger.apply(generation, RuntimeProjectionChange.RecordDecision(key("decision"), key("allow")), now),
                )
                assertEquals(
                    RuntimeProjectionResult.APPLIED,
                    ledger.apply(generation, RuntimeProjectionChange.RegisterDuty(key("duty"), key("notes.sync")), now),
                )
                assertEquals(
                    RuntimeProjectionResult.APPLIED,
                    ledger.apply(generation, RuntimeProjectionChange.ReleaseDuty(key("duty")), now),
                )
                assertEquals(6, ledger.events(taskId, start).count { it.type.name == "PROJECTION_CHANGED" })
                assertEquals(requireNotNull(ledger.snapshot(taskId)).cursor, ledger.events(taskId, TaskCursor.ZERO).last().cursor)
            }
            DatabaseFactory.openLocal(file).use { database ->
                val ledger = SqlTaskLedger(database)
                val generation = RuntimeTaskGeneration(taskId, 1)
                assertEquals(listOf("req-0", "req-1"), ledger.approvals(generation, null, 2).map { it.id.value })
                assertEquals(listOf("req-2"), ledger.approvals(generation, key("req-1"), 2).map { it.id.value })
                assertTrue(ledger.approvals(generation, key("req-2"), 2).isEmpty())
                assertEquals(RuntimeApprovalState.PENDING, ledger.approvals(generation, null, 10).first().state)
                val audit = ledger.audit(generation, TaskCursor.ZERO, 2)
                assertEquals(
                    listOf(RuntimeAuditAction.APPROVAL_REQUESTED, RuntimeAuditAction.APPROVAL_REQUESTED),
                    audit.map { it.action },
                )
                assertEquals(2, audit.size)
                val nextAudit = ledger.audit(generation, audit.last().cursor, 2)
                assertEquals(
                    listOf(RuntimeAuditAction.APPROVAL_REQUESTED, RuntimeAuditAction.DECISION_RECORDED),
                    nextAudit.map { it.action },
                )
                assertEquals(audit.last().cursor.value + 1, nextAudit.first().cursor.value)
                val duties = ledger.duties(generation, null, 10)
                assertEquals(1, duties.size)
                assertEquals("notes.sync", duties.single().purpose.value)
                assertEquals(now, duties.single().releasedAt)
                assertFalse(
                    ledger.events(taskId, TaskCursor.ZERO).any {
                        it.type.name != "CREATED" && it.type.name != "PROJECTION_CHANGED"
                    },
                )
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun duplicateSteadyStateIsUnchangedWithoutNewEvent() = runBlocking {
        val dir = Files.createTempDirectory("runtime-projection-stable").toFile()
        val taskId = TaskId.parse("stable-task")
        val generation = RuntimeTaskGeneration(taskId, 1)
        try {
            DatabaseFactory.openLocal(dir.resolve("runtime.db")).use { database ->
                val ledger = SqlTaskLedger(database)
                ledger.create(taskId, TaskSubmission(kind), now)
                val change = RuntimeProjectionChange.RequestApproval(key("approval"), key("operation"))
                assertEquals(RuntimeProjectionResult.APPLIED, ledger.apply(generation, change, now))
                val before = ledger.events(taskId, TaskCursor.ZERO).size
                assertEquals(RuntimeProjectionResult.UNCHANGED, ledger.apply(generation, change, now.plusSeconds(5)))
                assertEquals(before, ledger.events(taskId, TaskCursor.ZERO).size)
                assertEquals(1, ledger.approvals(generation, null, 10).size)
                assertEquals(1, ledger.audit(generation, TaskCursor.ZERO, 10).size)

                assertEquals(
                    RuntimeProjectionResult.APPLIED,
                    ledger.apply(generation, RuntimeProjectionChange.RegisterDuty(key("duty"), key("sync")), now),
                )
                val afterRegister = ledger.events(taskId, TaskCursor.ZERO).size
                assertEquals(
                    RuntimeProjectionResult.UNCHANGED,
                    ledger.apply(generation, RuntimeProjectionChange.RegisterDuty(key("duty"), key("sync")), now),
                )
                assertEquals(afterRegister, ledger.events(taskId, TaskCursor.ZERO).size)

                assertEquals(
                    RuntimeProjectionResult.APPLIED,
                    ledger.apply(generation, RuntimeProjectionChange.ReleaseDuty(key("duty")), now),
                )
                val afterRelease = ledger.events(taskId, TaskCursor.ZERO).size
                assertEquals(
                    RuntimeProjectionResult.UNCHANGED,
                    ledger.apply(generation, RuntimeProjectionChange.ReleaseDuty(key("duty")), now),
                )
                assertEquals(afterRelease, ledger.events(taskId, TaskCursor.ZERO).size)
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun conflictingSemanticsAreRejectedWithoutWriting() = runBlocking {
        val dir = Files.createTempDirectory("runtime-projection-conflict").toFile()
        val taskId = TaskId.parse("conflict-task")
        val generation = RuntimeTaskGeneration(taskId, 1)
        try {
            DatabaseFactory.openLocal(dir.resolve("runtime.db")).use { database ->
                val ledger = SqlTaskLedger(database)
                ledger.create(taskId, TaskSubmission(kind), now)
                assertEquals(
                    RuntimeProjectionResult.APPLIED,
                    ledger.apply(generation, RuntimeProjectionChange.RequestApproval(key("req"), key("op-a")), now),
                )
                assertEquals(
                    RuntimeProjectionResult.CONFLICT,
                    ledger.apply(generation, RuntimeProjectionChange.RequestApproval(key("req"), key("op-b")), now),
                )
                assertEquals(
                    RuntimeProjectionResult.APPLIED,
                    ledger.apply(generation, RuntimeProjectionChange.RecordDecision(key("dec"), key("allow")), now),
                )
                assertEquals(
                    RuntimeProjectionResult.CONFLICT,
                    ledger.apply(generation, RuntimeProjectionChange.RecordDecision(key("dec"), key("deny")), now),
                )
                assertEquals(
                    RuntimeProjectionResult.APPLIED,
                    ledger.apply(generation, RuntimeProjectionChange.ResolveApproval(key("req"), RuntimeApprovalState.APPROVED), now),
                )
                val before = ledger.events(taskId, TaskCursor.ZERO).size
                assertEquals(
                    RuntimeProjectionResult.CONFLICT,
                    ledger.apply(generation, RuntimeProjectionChange.ResolveApproval(key("req"), RuntimeApprovalState.DENIED), now),
                )
                assertEquals(before, ledger.events(taskId, TaskCursor.ZERO).size)
                assertEquals(
                    RuntimeProjectionResult.MISSING,
                    ledger.apply(generation, RuntimeProjectionChange.ResolveApproval(key("absent"), RuntimeApprovalState.APPROVED), now),
                )
                assertEquals(
                    RuntimeProjectionResult.MISSING,
                    ledger.apply(generation, RuntimeProjectionChange.ReleaseDuty(key("absent")), now),
                )
                assertEquals(RuntimeApprovalState.APPROVED, ledger.approvals(generation, null, 10).single().state)
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun generationsAndTasksWithTheSameRecordKeyStayIsolated() = runBlocking {
        val dir = Files.createTempDirectory("runtime-projection-keys").toFile()
        val first = TaskId.parse("task-a")
        val second = TaskId.parse("task-b")
        try {
            DatabaseFactory.openLocal(dir.resolve("runtime.db")).use { database ->
                val ledger = SqlTaskLedger(database)
                ledger.create(first, TaskSubmission(kind), now)
                ledger.create(second, TaskSubmission(kind), now)
                val one = RuntimeTaskGeneration(first, 1)
                val two = RuntimeTaskGeneration(second, 1)
                assertEquals(
                    RuntimeProjectionResult.APPLIED,
                    ledger.apply(one, RuntimeProjectionChange.RequestApproval(key("shared"), key("op-a")), now),
                )
                assertEquals(
                    RuntimeProjectionResult.CONFLICT,
                    // Same key, different operation, but a different task must not see the row.
                    ledger.apply(one, RuntimeProjectionChange.RequestApproval(key("shared"), key("op-b")), now),
                )
                assertEquals(
                    RuntimeProjectionResult.APPLIED,
                    ledger.apply(two, RuntimeProjectionChange.RequestApproval(key("shared"), key("op-b")), now),
                )
                assertEquals("op-a", ledger.approvals(one, null, 10).single().operation.value)
                assertEquals("op-b", ledger.approvals(two, null, 10).single().operation.value)
                assertTrue(ledger.approvals(RuntimeTaskGeneration(first, 2), null, 10).isEmpty())
                assertEquals(
                    RuntimeProjectionResult.STALE_GENERATION,
                    ledger.apply(RuntimeTaskGeneration(first, 2), RuntimeProjectionChange.RequestApproval(key("shared"), key("op-a")), now),
                )
                assertTrue(ledger.duties(one, null, 10).isEmpty())
                assertEquals(1, ledger.audit(one, TaskCursor.ZERO, 10).size)
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun terminalTaskKeepsCleanupPathsAndRejectsNewWork() = runBlocking {
        val dir = Files.createTempDirectory("runtime-projection-terminal").toFile()
        val taskId = TaskId.parse("terminal-task")
        try {
            DatabaseFactory.openLocal(dir.resolve("runtime.db")).use { database ->
                val ledger = SqlTaskLedger(database)
                ledger.create(taskId, TaskSubmission(kind), now)
                val generation = RuntimeTaskGeneration(taskId, 1)
                assertEquals(
                    RuntimeProjectionResult.APPLIED,
                    ledger.apply(generation, RuntimeProjectionChange.RequestApproval(key("pending"), key("operation")), now),
                )
                assertEquals(
                    RuntimeProjectionResult.APPLIED,
                    ledger.apply(generation, RuntimeProjectionChange.RegisterDuty(key("duty"), key("sync")), now),
                )
                val claim = ledger.apply(taskId, LedgerCommand.Claim("owner", now.plusSeconds(30)), now) as LedgerResult.Applied
                val token = requireNotNull(claim.snapshot.leaseToken())
                ledger.apply(taskId, LedgerCommand.Transition(token, RuntimeState.RUNNING), now)
                ledger.apply(taskId, LedgerCommand.Finish(token, RuntimeState.COMPLETED), now)
                assertTrue(requireNotNull(ledger.snapshot(taskId)).state.terminal)

                assertEquals(
                    RuntimeProjectionResult.CLOSED,
                    ledger.apply(generation, RuntimeProjectionChange.RequestApproval(key("new"), key("operation")), now),
                )
                assertEquals(
                    RuntimeProjectionResult.CLOSED,
                    ledger.apply(generation, RuntimeProjectionChange.RegisterDuty(key("new-duty"), key("sync")), now),
                )
                assertEquals(
                    RuntimeProjectionResult.CLOSED,
                    ledger.apply(generation, RuntimeProjectionChange.ResolveApproval(key("pending"), RuntimeApprovalState.APPROVED), now),
                )
                assertEquals(
                    RuntimeProjectionResult.APPLIED,
                    ledger.apply(generation, RuntimeProjectionChange.ResolveApproval(key("pending"), RuntimeApprovalState.CANCELLED), now),
                )
                val afterCancel = ledger.events(taskId, TaskCursor.ZERO).size
                assertEquals(
                    RuntimeProjectionResult.UNCHANGED,
                    ledger.apply(generation, RuntimeProjectionChange.ResolveApproval(key("pending"), RuntimeApprovalState.CANCELLED), now),
                )
                assertEquals(afterCancel, ledger.events(taskId, TaskCursor.ZERO).size)
                assertEquals(
                    RuntimeProjectionResult.APPLIED,
                    ledger.apply(generation, RuntimeProjectionChange.ReleaseDuty(key("duty")), now),
                )
                assertEquals(
                    RuntimeProjectionResult.APPLIED,
                    ledger.apply(generation, RuntimeProjectionChange.RecordDecision(key("decision"), key("allow")), now),
                )
                assertEquals(0, ledger.duties(generation, null, 10).count { it.releasedAt == null })
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun logsCarryNoBusinessPayloadAndLoggerFailureCannotFailApply() = runBlocking {
        val dir = Files.createTempDirectory("runtime-projection-log").toFile()
        val taskId = TaskId.parse("log-task")
        val generation = RuntimeTaskGeneration(taskId, 1)
        val records = Collections.synchronizedList(mutableListOf<LogRecord>())
        val logger = object : Logger {
            override fun log(record: LogRecord) {
                records.add(record)
                error("sink is unavailable")
            }
        }
        try {
            DatabaseFactory.openLocal(dir.resolve("runtime.db")).use { database ->
                val ledger = SqlTaskLedger(database, logger)
                ledger.create(taskId, TaskSubmission(kind), now)
                val secretOperation = key("operation-secret-value")
                assertEquals(
                    RuntimeProjectionResult.APPLIED,
                    ledger.apply(generation, RuntimeProjectionChange.RequestApproval(key("approval"), secretOperation), now),
                )
                assertEquals(
                    RuntimeProjectionResult.CONFLICT,
                    ledger.apply(generation, RuntimeProjectionChange.RequestApproval(key("approval"), key("other")), now),
                )
                assertEquals(1, ledger.approvals(generation, null, 10).size)
                val projectionRecords = records.filter { it.event.startsWith("runtime.projection.") }
                // Exactly two projection records: one APPLIED and one CONFLICT; the failing sink
                // must not add anything and must not fail the caller.
                assertEquals(2, projectionRecords.size)
                val applied = projectionRecords.single { it.event == "runtime.projection.applied" }
                val rejected = projectionRecords.single { it.event == "runtime.projection.rejected" }
                assertEquals(JsonValue.text("projection"), applied.fields["phase"])
                assertEquals(JsonValue.text("services/runtime/data"), applied.fields["owner"])
                assertEquals(JsonValue.text("RequestApproval"), applied.fields["change"])
                assertEquals(JsonValue.text("APPLIED"), applied.fields["result"])
                assertEquals(JsonValue.text("CONFLICT"), rejected.fields["result"])
                assertTrue(applied.fields["duration_ms"] is JsonValue.Number)
                assertTrue(rejected.fields["duration_ms"] is JsonValue.Number)
                assertTrue(projectionRecords.all { it.taskId == taskId })
                val text = records.toString()
                assertFalse(text.contains("operation-secret-value"))
                assertFalse(text.contains("payload") && text.contains("secret"))
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun lateProjectionDoesNotMoveTaskUpdatedAtOrWeakenLease() = runBlocking {
        val dir = Files.createTempDirectory("runtime-projection-clock").toFile()
        val taskId = TaskId.parse("clock-task")
        val generation = RuntimeTaskGeneration(taskId, 1)
        try {
            DatabaseFactory.openLocal(dir.resolve("runtime.db")).use { database ->
                val ledger = SqlTaskLedger(database)
                ledger.create(taskId, TaskSubmission(kind), now)
                val leaseUntil = now.plusSeconds(60)
                val claim = ledger.apply(taskId, LedgerCommand.Claim("owner", leaseUntil), now) as LedgerResult.Applied
                val before = requireNotNull(ledger.snapshot(taskId))
                assertEquals(now, before.times.updatedAt)
                val late = now.plusSeconds(10)
                assertEquals(
                    RuntimeProjectionResult.APPLIED,
                    ledger.apply(generation, RuntimeProjectionChange.RequestApproval(key("approval"), key("op")), late),
                )
                val after = requireNotNull(ledger.snapshot(taskId))
                assertEquals(before.times.updatedAt, after.times.updatedAt)
                assertEquals(leaseUntil, after.ownership.leaseUntil)
                assertTrue(after.cursor.value > before.cursor.value)
                val token = requireNotNull(after.leaseToken())
                assertTrue(ledger.apply(taskId, LedgerCommand.Renew(token, leaseUntil.plusSeconds(30)), late) is LedgerResult.Applied)
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun key(value: String): RuntimeRecordKey = RuntimeRecordKey.parse(value)
}

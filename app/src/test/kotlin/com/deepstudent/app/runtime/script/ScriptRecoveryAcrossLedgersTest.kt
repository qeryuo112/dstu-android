package com.deepstudent.app.runtime.script

import com.deepstudent.core.common.id.TaskId
import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.core.common.logging.NoopLogger
import com.deepstudent.core.common.time.MonotonicClock
import com.deepstudent.core.common.time.WallClock
import com.deepstudent.platform.database.DatabaseFactory
import com.deepstudent.platform.database.SqlDatabase
import com.deepstudent.services.runtime.api.DeadOwnerPredicate
import com.deepstudent.services.runtime.api.RecoveryDecision
import com.deepstudent.services.runtime.api.RecoveryHandler
import com.deepstudent.services.runtime.api.RecoveryPublisher
import com.deepstudent.services.runtime.api.RecoveryRegistration
import com.deepstudent.services.runtime.api.RuntimeEventType
import com.deepstudent.services.runtime.api.RuntimeState
import com.deepstudent.services.runtime.api.RuntimeTaskHandler
import com.deepstudent.services.runtime.api.TaskCursor
import com.deepstudent.services.runtime.api.TaskKind
import com.deepstudent.services.runtime.api.TaskSubmission
import com.deepstudent.services.runtime.backend.HandlerRegistry
import com.deepstudent.services.runtime.backend.LeaseManager
import com.deepstudent.services.runtime.backend.LedgerCommand
import com.deepstudent.services.runtime.backend.LedgerResult
import com.deepstudent.services.runtime.backend.TaskLedger
import com.deepstudent.services.runtime.backend.TaskSupervisor
import com.deepstudent.services.runtime.backend.application.recovery.RecoveryActions
import com.deepstudent.services.runtime.backend.application.recovery.RecoveryCoordinator
import com.deepstudent.services.runtime.backend.leaseToken
import com.deepstudent.services.runtime.data.SqlTaskLedger
import com.deepstudent.services.script.backend.ScriptCompletionOutbox
import com.deepstudent.services.script.backend.ScriptReceipt
import com.deepstudent.services.script.backend.ScriptReceiptContent
import com.deepstudent.services.script.backend.ScriptTerminalState
import com.deepstudent.services.script.data.SqlScriptCompletionOutbox
import com.deepstudent.services.script.data.SqlScriptReceiptStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.Duration
import java.time.Instant

/**
 * Cross-ledger acceptance over real SQLite: runtime.db and script.db are both closed and
 * reopened between attempts. A failure in the ledger reconcile or in the acknowledgement must
 * leave the business fact pending, project it exactly once, and confirm it only afterwards.
 */
class ScriptRecoveryAcrossLedgersTest {
    private val kind = TaskKind.parse("script.ptc_minimal")
    private val now = Instant.parse("2026-11-17T00:00:00Z")
    private val task = TaskId.parse("script-task")

    @Test
    fun reconcileFailureRecoversOnceAfterBothDatabasesReopen() = runBlocking {
        val dir = Files.createTempDirectory("script-recovery-reconcile").toFile()
        val runtimeFile = dir.resolve("runtime.db")
        try {
            withDatabases(dir) { runtimeDb, scriptDb ->
                seed(runtimeDb, scriptDb)
                // The receipt trigger, not a backfill, produced the pending fact.
                assertEquals(listOf(FACT_ID), outbox(scriptDb).pending(CONSUMER, 10).map { it.factId })
                runRecovery(runtimeDb, scriptDb, failingReconcile = true)
            }
            // Nothing was projected and no acknowledgement happened before the ledger write.
            assertEquals(0, reconciledEvents(runtimeFile))
            withDatabases(dir) { _, scriptDb ->
                assertEquals(1, receiptCount(scriptDb))
                assertEquals(0L, outbox(scriptDb).consumerCursor(CONSUMER))
                assertEquals(listOf(FACT_ID), outbox(scriptDb).pending(CONSUMER, 10).map { it.factId })
            }

            withDatabases(dir) { runtimeDb, scriptDb ->
                runRecovery(runtimeDb, scriptDb)
            }
            assertEquals(1, reconciledEvents(runtimeFile))
            assertEquals(RuntimeState.COMPLETED, snapshot(runtimeFile).state)
            assertEquals(FACT_ID, snapshot(runtimeFile).execution.businessFactId)
            assertEquals("ptc:completed:digest", snapshot(runtimeFile).execution.receipt)
            withDatabases(dir) { _, scriptDb ->
                assertEquals(1, receiptCount(scriptDb))
                assertTrue(outbox(scriptDb).pending(CONSUMER, 10).isEmpty())
                assertEquals(1L, outbox(scriptDb).consumerCursor(CONSUMER))
            }

            // A further recovery pass must not append a second projection event.
            withDatabases(dir) { runtimeDb, scriptDb ->
                runRecovery(runtimeDb, scriptDb)
            }
            assertEquals(1, reconciledEvents(runtimeFile))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun acknowledgementFailureProjectsOnceAndConfirmsOnlyAfterReopen() = runBlocking {
        val dir = Files.createTempDirectory("script-recovery-ack").toFile()
        val runtimeFile = dir.resolve("runtime.db")
        try {
            withDatabases(dir) { runtimeDb, scriptDb ->
                seed(runtimeDb, scriptDb)
                runRecovery(runtimeDb, scriptDb, failingAck = true)
            }
            // Projection committed, but the fact stays pending and unconfirmed.
            assertEquals(1, reconciledEvents(runtimeFile))
            withDatabases(dir) { _, scriptDb ->
                assertEquals(1, receiptCount(scriptDb))
                assertEquals(0L, outbox(scriptDb).consumerCursor(CONSUMER))
                assertEquals(listOf(FACT_ID), outbox(scriptDb).pending(CONSUMER, 10).map { it.factId })
            }

            withDatabases(dir) { runtimeDb, scriptDb ->
                runRecovery(runtimeDb, scriptDb)
            }
            assertEquals(1, reconciledEvents(runtimeFile))
            withDatabases(dir) { _, scriptDb ->
                assertEquals(1, receiptCount(scriptDb))
                assertTrue(outbox(scriptDb).pending(CONSUMER, 10).isEmpty())
                assertEquals(1L, outbox(scriptDb).consumerCursor(CONSUMER))
                // An untouched consumer still sees its own copy of the same fact.
                assertEquals(listOf(FACT_ID), outbox(scriptDb).pending("other.consumer", 10).map { it.factId })
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    /** Installs the outbox trigger before the receipt so insertion goes through the trigger path. */
    private suspend fun seed(runtimeDb: SqlDatabase, scriptDb: SqlDatabase) {
        val store = SqlScriptReceiptStore(scriptDb)
        SqlScriptCompletionOutbox(scriptDb)
        store.commit(receipt())
        val ledger = SqlTaskLedger(runtimeDb)
        ledger.create(task, TaskSubmission(kind, JsonValue.text("payload")), now)
        val claim = ledger.apply(task, LedgerCommand.Claim("old-owner", now.plusSeconds(60)), now) as LedgerResult.Applied
        val token = requireNotNull(claim.snapshot.leaseToken())
        ledger.apply(task, LedgerCommand.Transition(token, RuntimeState.RUNNING), now)
        ledger.apply(task, LedgerCommand.Checkpoint(token, JsonValue.text("partial")), now)
    }

    private suspend fun runRecovery(
        runtimeDb: SqlDatabase,
        scriptDb: SqlDatabase,
        failingReconcile: Boolean = false,
        failingAck: Boolean = false,
    ) {
        val ledger: TaskLedger =
            if (failingReconcile) FailingReconcileLedger(SqlTaskLedger(runtimeDb)) else SqlTaskLedger(runtimeDb)
        val outbox = FlakyOutbox(SqlScriptCompletionOutbox(scriptDb), failingAck)
        val source = ScriptOutboxCompletionSource(outbox, SqlScriptReceiptStore(scriptDb))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            RecoveryCoordinator(
                RecoveryActions(
                    ledger,
                    TaskSupervisor(
                        scope,
                        ledger,
                        LeaseManager(ledger, clock, "recovery", LEASE),
                        HandlerRegistry(
                            mapOf(
                                kind to RuntimeTaskHandler { _, _ ->
                                    error("recovery must not execute business again")
                                },
                            ),
                            mapOf(kind to source),
                        ),
                        clock,
                        NoopLogger,
                    ),
                    clock,
                    "recovery",
                    LEASE,
                    DeadOwnerPredicate { true },
                ),
                listOf(RecoveryRegistration(kind, source, RecoveryHandler { RecoveryDecision.Interrupted })),
                RecoveryPublisher { },
                NoopLogger,
                MonotonicClock { 1_000_000L },
                10,
            ).recover()
        } finally {
            scope.cancel()
        }
    }

    private suspend fun withDatabases(dir: File, block: suspend (SqlDatabase, SqlDatabase) -> Unit) {
        DatabaseFactory.openLocal(dir.resolve("script.db")).use { scriptDb ->
            DatabaseFactory.openLocal(dir.resolve("runtime.db")).use { runtimeDb -> block(runtimeDb, scriptDb) }
        }
    }

    private suspend fun receiptCount(scriptDb: SqlDatabase): Int =
        scriptDb.readSnapshot { query("SELECT COUNT(*) FROM script_receipts") { it.long(0) }.single().toInt() }

    private fun outbox(scriptDb: SqlDatabase): ScriptCompletionOutbox = SqlScriptCompletionOutbox(scriptDb)

    private suspend fun snapshot(runtimeFile: File) = DatabaseFactory.openLocal(runtimeFile).use { database ->
        requireNotNull(SqlTaskLedger(database).snapshot(task))
    }

    private suspend fun reconciledEvents(runtimeFile: File): Int = DatabaseFactory.openLocal(runtimeFile).use { database ->
        SqlTaskLedger(database).events(task, TaskCursor.ZERO)
            .count { it.type == RuntimeEventType.RECONCILED }
    }

    private fun receipt(): ScriptReceipt = ScriptReceipt(
        factId = FACT_ID,
        taskId = task,
        generation = 1,
        state = ScriptTerminalState.COMPLETED,
        receipt = "ptc:completed:digest",
        committedAt = now,
        content = ScriptReceiptContent(JsonValue.obj("ok" to JsonValue.integer(1)), emptyList(), null, 2, 0),
    )

    /** Every reconcile is rejected, so one recovery pass cannot fall through to success. */
    private class FailingReconcileLedger(private val delegate: TaskLedger) : TaskLedger by delegate {
        override suspend fun apply(taskId: TaskId, command: LedgerCommand, now: Instant): LedgerResult {
            if (command is LedgerCommand.Reconcile) error("private SQL error")
            return delegate.apply(taskId, command, now)
        }
    }

    private class FlakyOutbox(private val delegate: ScriptCompletionOutbox, private val failAck: Boolean) :
        ScriptCompletionOutbox by delegate {
        override suspend fun acknowledge(consumerId: String, factId: String) {
            if (failAck) error("private receipt ack")
            delegate.acknowledge(consumerId, factId)
        }
    }

    private companion object {
        const val CONSUMER = "runtime.recovery"
        const val FACT_ID = "script-ptc:script-task:1"
        val LEASE: Duration = Duration.ofMinutes(2)
        val clock: WallClock = WallClock { Instant.parse("2026-11-17T00:02:00Z") }
    }
}

package com.deepstudent.services.runtime.data

import com.deepstudent.core.common.id.TaskId
import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.core.common.time.WallClock
import com.deepstudent.platform.database.DatabaseFactory
import com.deepstudent.platform.database.SqlDatabase
import com.deepstudent.services.runtime.api.BusinessCompletion
import com.deepstudent.services.runtime.api.RuntimeEventType
import com.deepstudent.services.runtime.api.RuntimeState
import com.deepstudent.services.runtime.api.TaskCursor
import com.deepstudent.services.runtime.api.TaskKind
import com.deepstudent.services.runtime.api.TaskSubmission
import com.deepstudent.services.runtime.backend.LeaseManager
import com.deepstudent.services.runtime.backend.LedgerCommand
import com.deepstudent.services.runtime.backend.LedgerRejection
import com.deepstudent.services.runtime.backend.LedgerResult
import com.deepstudent.services.runtime.backend.RecoveryLease
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.Duration
import java.time.Instant

/**
 * S2.20 against the real bundled SQLite runtime.db. Two independently opened DatabaseFactory
 * handles stand in for the UI and Worker entry points; both use the existing SqlTaskLedger
 * submission contract. In this process DatabaseRegistry shares one serialized writer per
 * canonical file, so the race outcome is determined by the ledger transaction, not by two
 * unsynchronized connections. Cross-process contention stays SQLite's own lock contract.
 */
class S220SqliteLeaseTest {
    private val start = Instant.parse("2026-11-23T00:00:00Z")
    private val kind = TaskKind.parse("s220.sqlite")
    private val task = TaskId.parse("s220-task")

    @Test
    fun concurrentUiAndWorkerClaimsYieldExactlyOneExecutionOwner() = runBlocking {
        withDatabase { _, first, second ->
            val ui = SqlTaskLedger(first)
            val worker = SqlTaskLedger(second)
            ui.create(task, TaskSubmission(kind), start)
            val clock = WallClock { start }
            val managers = (1..12).map { index ->
                val ledger = if (index % 2 == 0) ui else worker
                LeaseManager(ledger, clock, if (index % 2 == 0) "ui-$index" else "worker-$index", Duration.ofSeconds(10))
            }
            val tokens = managers.map { manager ->
                async(Dispatchers.IO) { manager.claim(task) }
            }.awaitAll().filterNotNull()

            assertEquals(1, tokens.size)
            val stored = requireNotNull(ui.snapshot(task))
            assertEquals(tokens.single().ownerId, stored.ownership.ownerId)
            assertEquals(1L, stored.ownership.ownerEpoch)
            assertEquals(RuntimeState.CREATED, stored.state)
            assertEquals(1, worker.events(task, TaskCursor.ZERO).count { it.type == RuntimeEventType.LEASE_CHANGED })
        }
    }

    @Test
    fun expiredTokenCannotRenewOrWriteAfterExpectedOwnershipTakeover() = runBlocking {
        var now = start
        withDatabase { _, first, second ->
            val ui = SqlTaskLedger(first)
            val worker = SqlTaskLedger(second)
            ui.create(task, TaskSubmission(kind), now)
            val uiLease = LeaseManager(ui, WallClock { now }, "ui", Duration.ofSeconds(10))
            val token = requireNotNull(uiLease.claim(task))
            ui.apply(task, LedgerCommand.Transition(token, RuntimeState.RUNNING), now)
            ui.apply(task, LedgerCommand.Checkpoint(token, JsonValue.text("partial")), now)

            val expired = requireNotNull(ui.snapshot(task)).ownership
            now = start.plusSeconds(10)
            val recovery = requireNotNull(
                LeaseManager(worker, WallClock { now }, "recovery", Duration.ofSeconds(30)).takeOverExpired(task, expired),
            )
            assertTrue(recovery.recoveryOnly)
            assertEquals(RecoveryLease.ownerId("recovery"), recovery.ownerId)
            assertTrue(requireNotNull(worker.snapshot(task)).ownership.ownerId!!.startsWith(RecoveryLease.OWNER_PREFIX))

            assertEquals(LedgerRejection.STALE_OWNER, rejection(worker, task, LedgerCommand.Renew(token, now.plusSeconds(60))))
            assertEquals(LedgerRejection.STALE_OWNER, rejection(worker, task, LedgerCommand.Checkpoint(token, JsonValue.text("late"))))
            assertEquals(LedgerRejection.STALE_OWNER, rejection(worker, task, LedgerCommand.Transition(token, RuntimeState.RUNNING)))
            assertEquals(LedgerRejection.STALE_OWNER, rejection(worker, task, LedgerCommand.Release(token)))

            val unaffected = requireNotNull(worker.snapshot(task))
            assertEquals(RuntimeState.RUNNING, unaffected.state)
            assertEquals(JsonValue.text("partial"), unaffected.execution.checkpoint)
            assertEquals(expired.ownerEpoch + 1, unaffected.ownership.ownerEpoch)
            assertEquals(1, worker.events(task, TaskCursor.ZERO).count { it.type == RuntimeEventType.CHECKPOINT })
        }
    }

    @Test
    fun renewalBlocksTakeoverAndTakeoverBlocksStaleRenewal() = runBlocking {
        var now = start
        withDatabase { _, first, second ->
            val ui = SqlTaskLedger(first)
            val recovery = SqlTaskLedger(second)
            ui.create(task, TaskSubmission(kind), now)
            val uiLease = LeaseManager(ui, WallClock { now }, "ui", Duration.ofSeconds(10))
            val token = requireNotNull(uiLease.claim(task))
            val original = requireNotNull(ui.snapshot(task)).ownership

            now = start.plusSeconds(5)
            val renewed = uiLease.renew(token)
            assertEquals(start.plusSeconds(15), renewed.ownership.leaseUntil)
            val recoveryLease = LeaseManager(recovery, WallClock { now }, "recovery", Duration.ofSeconds(30))

            assertNull(recoveryLease.takeOverExpired(task, original))
            now = start.plusSeconds(14)
            assertNull(recoveryLease.takeOverExpired(task, renewed.ownership))

            now = start.plusSeconds(15)
            val taken = requireNotNull(recoveryLease.takeOverExpired(task, renewed.ownership))
            assertTrue(taken.recoveryOnly)
            assertEquals(LedgerRejection.STALE_OWNER, rejection(ui, task, LedgerCommand.Renew(token, now.plusSeconds(90))))
            assertEquals(
                3,
                ui.events(task, TaskCursor.ZERO).count { it.type == RuntimeEventType.LEASE_CHANGED },
            )
        }
    }

    @Test
    fun duplicateCancelWritesOneRequestAndOneTerminalState() = runBlocking {
        withDatabase { _, first, second ->
            val ui = SqlTaskLedger(first)
            val worker = SqlTaskLedger(second)
            val clock = WallClock { start }
            ui.create(task, TaskSubmission(kind), clock.now())
            val token = requireNotNull(LeaseManager(ui, clock, "ui", Duration.ofSeconds(30)).claim(task))
            ui.apply(task, LedgerCommand.Transition(token, RuntimeState.RUNNING), clock.now())

            val results = listOf(ui, worker, ui, worker).map { ledger ->
                async(Dispatchers.IO) { ledger.apply(task, LedgerCommand.RequestCancel, clock.now()) }
            }.awaitAll()
            assertEquals(1, results.count { it is LedgerResult.Applied })
            assertEquals(3, results.count { it is LedgerResult.Unchanged })
            assertEquals(1, ui.events(task, TaskCursor.ZERO).count { it.type == RuntimeEventType.CANCEL_REQUESTED })

            val finished = ui.apply(task, LedgerCommand.Finish(token, RuntimeState.COMPLETED), clock.now())
            assertEquals(RuntimeState.CANCELLED, (finished as LedgerResult.Applied).snapshot.state)
            assertEquals(LedgerRejection.TERMINAL, rejection(worker, task, LedgerCommand.RequestCancel))
            assertEquals(LedgerRejection.TERMINAL, rejection(worker, task, LedgerCommand.RequestCancelGeneration(1)))
            assertEquals(LedgerRejection.TERMINAL, rejection(worker, task, LedgerCommand.Finish(token, RuntimeState.COMPLETED)))
            assertEquals(RuntimeState.CANCELLED, requireNotNull(worker.snapshot(task)).state)
            assertEquals(2, worker.events(task, TaskCursor.ZERO).count { it.type == RuntimeEventType.STATE_CHANGED })
        }
    }

    @Test
    fun businessFactAfterTakeoverIsWrittenOnceAndSurvivesReopen() = runBlocking {
        var now = start
        val directory = Files.createTempDirectory("s220-fact").toFile()
        try {
            val file = directory.resolve("runtime.db")
            DatabaseFactory.openLocal(file).use { database ->
                val ledger = SqlTaskLedger(database)
                ledger.create(task, TaskSubmission(kind), now)
                val token = requireNotNull(LeaseManager(ledger, WallClock { now }, "ui", Duration.ofSeconds(10)).claim(task))
                ledger.apply(task, LedgerCommand.Transition(token, RuntimeState.RUNNING), now)
                ledger.apply(task, LedgerCommand.Checkpoint(token, JsonValue.text("half")), now)
                val expired = requireNotNull(ledger.snapshot(task)).ownership

                now = start.plusSeconds(10)
                val recovery = requireNotNull(
                    LeaseManager(ledger, WallClock { now }, "recovery", Duration.ofSeconds(30)).takeOverExpired(task, expired),
                )
                val fact = BusinessCompletion("fact-1", task, 1, RuntimeState.COMPLETED, "receipt-1", now)
                assertTrue(ledger.apply(task, LedgerCommand.Reconcile(fact, recovery), now) is LedgerResult.Applied)
                assertTrue(ledger.apply(task, LedgerCommand.Reconcile(fact, recovery), now) is LedgerResult.Unchanged)
                assertEquals(
                    LedgerRejection.FACT_CONFLICT,
                    rejection(ledger, task, LedgerCommand.Reconcile(fact.copy(factId = "fact-2"), recovery)),
                )
                assertEquals(1, ledger.events(task, TaskCursor.ZERO).count { it.type == RuntimeEventType.RECONCILED })
                assertNull(requireNotNull(ledger.snapshot(task)).ownership.ownerId)
            }
            DatabaseFactory.openLocal(file).use { database ->
                val reopened = SqlTaskLedger(database)
                val snapshot = requireNotNull(reopened.snapshot(task))
                assertEquals(RuntimeState.COMPLETED, snapshot.state)
                assertEquals("receipt-1", snapshot.execution.receipt)
                assertEquals("fact-1", snapshot.execution.businessFactId)
                assertEquals(JsonValue.text("half"), snapshot.execution.checkpoint)
                assertNull(snapshot.ownership.ownerId)
                assertEquals(LedgerRejection.TERMINAL, rejection(reopened, task, LedgerCommand.RequestCancel))
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun explicitReleaseHandsOffToWorkerAndOldTokenStaysFenced() = runBlocking {
        withDatabase { _, first, second ->
            val ui = SqlTaskLedger(first)
            val worker = SqlTaskLedger(second)
            val clock = WallClock { start }
            ui.create(task, TaskSubmission(kind), clock.now())
            val uiLease = LeaseManager(ui, clock, "ui", Duration.ofSeconds(30))
            val token = requireNotNull(uiLease.claim(task))
            ui.apply(task, LedgerCommand.Transition(token, RuntimeState.RUNNING), clock.now())
            assertTrue(uiLease.release(token))
            assertTrue(uiLease.release(token))
            val released = requireNotNull(ui.snapshot(task))
            assertNull(released.ownership.ownerId)
            assertEquals(RuntimeState.RUNNING, released.state)

            val workerToken = requireNotNull(LeaseManager(worker, clock, "worker", Duration.ofSeconds(30)).claim(task))
            assertTrue(workerToken.ownerEpoch > token.ownerEpoch)
            assertEquals(LedgerRejection.STALE_OWNER, rejection(worker, task, LedgerCommand.Checkpoint(token, null)))
            assertEquals(LedgerRejection.STALE_OWNER, rejection(worker, task, LedgerCommand.Release(token)))
            assertNotNull(worker.snapshot(task)?.execution)
            assertEquals("worker", requireNotNull(worker.snapshot(task)).ownership.ownerId)
        }
    }

    private suspend fun rejection(ledger: SqlTaskLedger, taskId: TaskId, command: LedgerCommand): LedgerRejection =
        (ledger.apply(taskId, command, start.plusSeconds(40)) as LedgerResult.Rejected).reason

    private suspend fun withDatabase(block: suspend (File, SqlDatabase, SqlDatabase) -> Unit) {
        val directory = Files.createTempDirectory("s220-sqlite").toFile()
        try {
            val file = directory.resolve("runtime.db")
            DatabaseFactory.openLocal(file).use { first ->
                DatabaseFactory.openLocal(file).use { second ->
                    block(file, first, second)
                }
            }
        } finally {
            directory.deleteRecursively()
        }
    }
}

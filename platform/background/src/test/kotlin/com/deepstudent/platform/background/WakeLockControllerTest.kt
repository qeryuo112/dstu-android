package com.deepstudent.platform.background

import com.deepstudent.core.common.id.CorrelationId
import com.deepstudent.core.common.id.TaskId
import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.core.common.logging.LogRecord
import com.deepstudent.core.common.logging.Logger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class WakeLockControllerTest {
    @Test
    fun oneSegmentPerOwnerAndPhaseAndReuseNeverExtendsTheDeadline() {
        val fixture = Fixture()
        val first = fixture.controller.acquire(fixture.owner, WakePhase.WORK) as WakeAcquireResult.Held
        fixture.clock.advance(1_000)
        val second = fixture.controller.acquire(fixture.owner, WakePhase.WORK) as WakeAcquireResult.Reused
        assertSame(first.lease, second.lease)
        assertEquals(1, fixture.locks.acquireCount)
        assertEquals(first.lease.deadlineMillis, second.lease.deadlineMillis)
        assertEquals(listOf(600_000L), fixture.locks.created.single().acquiredTimeouts)
        assertEquals(listOf("acquire", "reuse"), fixture.categories())
    }

    @Test
    fun aDifferentPhaseIsAnIndependentSegment() {
        val fixture = Fixture()
        fixture.controller.acquire(fixture.owner, WakePhase.WORK)
        fixture.controller.acquire(fixture.owner, WakePhase.FINALIZE)
        assertEquals(setOf(WakePhase.WORK, WakePhase.FINALIZE), fixture.controller.heldPhases(fixture.owner))
        assertEquals(2, fixture.locks.acquireCount)
    }

    @Test
    fun segmentCapIsTheSystemTimeout() {
        val fixture = Fixture(limits = { WakeLockLimits(segmentMillis = 30_000, budgetMillis = 100_000) })
        val held = fixture.controller.acquire(fixture.owner, WakePhase.WORK) as WakeAcquireResult.Held
        assertEquals(listOf(30_000L), fixture.locks.created.single().acquiredTimeouts)
        assertEquals(31_000L, held.lease.deadlineMillis)
        assertTrue(fixture.logs.last().field("capMs") == "30000")
    }

    @Test
    fun timedOutSegmentIsDetectedExpiredAndChargedInFull() {
        val fixture = Fixture(limits = { WakeLockLimits(segmentMillis = 5_000, budgetMillis = 100_000) })
        fixture.controller.acquire(fixture.owner, WakePhase.WORK)
        fixture.locks.created.single().held = false
        fixture.clock.advance(9_000)
        assertTrue(fixture.controller.heldPhases(fixture.owner).isEmpty())
        val expiry = fixture.logs.last()
        assertEquals("expire", expiry.field("category"))
        assertEquals("timeout", expiry.field("reason"))
        assertEquals("5000", expiry.field("elapsedMs"))
        assertEquals("95000", expiry.field("remainingMs"))
        assertEquals(1, fixture.locks.created.single().releaseCount)
    }

    @Test
    fun revokedSegmentIsReportedSeparatelyFromATimeout() {
        val fixture = Fixture()
        fixture.controller.acquire(fixture.owner, WakePhase.SESSION)
        fixture.locks.created.single().held = false
        fixture.clock.advance(1_000)
        assertEquals(setOf<WakeOwner>(), fixture.controller.activeOwners())
        assertEquals("revoked", fixture.logs.last().field("reason"))
        assertEquals("1000", fixture.logs.last().field("elapsedMs"))
    }

    @Test
    fun releasingEarlyRefundsTheUnusedReservationAndTheRestStaysBounded() {
        val fixture = Fixture(limits = { WakeLockLimits(segmentMillis = 1_000, budgetMillis = 1_500) })
        val first = fixture.controller.acquire(fixture.owner, WakePhase.WORK) as WakeAcquireResult.Held
        fixture.clock.advance(400)
        first.lease.release(WakeReleaseReason.SEGMENT_END)
        val release = fixture.logs.last()
        assertEquals("release", release.field("category"))
        assertEquals("400", release.field("elapsedMs"))
        assertEquals("1100", release.field("remainingMs"))
        // The next segment still reserves at most one full segment, not the whole remainder.
        val second = fixture.controller.acquire(fixture.owner, WakePhase.WORK) as WakeAcquireResult.Held
        assertEquals(1_000L, fixture.locks.created[1].acquiredTimeouts.single())
        fixture.clock.advance(1_500)
        assertEquals(emptySet<WakePhase>(), fixture.controller.heldPhases(fixture.owner))
        assertEquals("100", fixture.logs.last().field("remainingMs"))
        // Only the leftover budget can be reserved now.
        fixture.controller.acquire(fixture.owner, WakePhase.WORK)
        assertEquals(100L, fixture.locks.created[2].acquiredTimeouts.single())
        assertEquals(2_400L, second.lease.deadlineMillis)
    }

    @Test
    fun exhaustedBudgetDeniesFurtherSegments() {
        val fixture = Fixture(limits = { WakeLockLimits(segmentMillis = 1_000, budgetMillis = 1_000) })
        val held = fixture.controller.acquire(fixture.owner, WakePhase.WORK) as WakeAcquireResult.Held
        fixture.clock.advance(1_000)
        held.lease.release()
        val denied = fixture.controller.acquire(fixture.owner, WakePhase.WORK)
        assertEquals(WakeAcquireResult.Denied(WakeDenial.BUDGET_EXHAUSTED), denied)
        assertEquals("deny", fixture.logs.last().field("category"))
        assertEquals("budget_exhausted", fixture.logs.last().field("reason"))
        assertEquals(1, fixture.locks.created.size)
    }

    @Test
    fun aNewGenerationStartsAFreshBudgetBecauseItIsANewOwner() {
        val fixture = Fixture(limits = { WakeLockLimits(segmentMillis = 1_000, budgetMillis = 1_000) })
        val first = fixture.controller.acquire(fixture.owner, WakePhase.WORK) as WakeAcquireResult.Held
        fixture.clock.advance(1_000)
        first.lease.release()
        val nextGeneration = wakeOwner(ForegroundDuty(TaskId.parse("task-1"), 2, null, ForegroundPurpose.DATA_SYNC))
        assertTrue(fixture.controller.acquire(nextGeneration, WakePhase.WORK) is WakeAcquireResult.Held)
    }

    @Test
    fun platformRejectionIsDeniedAndReleasesTheHandle() {
        val fixture = Fixture(rejectAcquire = true)
        assertEquals(WakeAcquireResult.Denied(WakeDenial.PLATFORM_REJECTED), fixture.controller.acquire(fixture.owner, WakePhase.WORK))
        assertEquals(1, fixture.locks.created.single().releaseCount)
        assertTrue(fixture.controller.heldPhases(fixture.owner).isEmpty())
        assertEquals("platform_rejected", fixture.logs.last().field("reason"))
    }

    @Test
    fun releaseOwnerOnlyTouchesThatOwnerAndReleaseAllClearsEverything() {
        val fixture = Fixture()
        val other = WakeOwner(WakeOwnerKind.CONTINUOUS, "session-2")
        fixture.controller.acquire(fixture.owner, WakePhase.WORK)
        fixture.controller.acquire(other, WakePhase.SESSION)
        fixture.controller.release(fixture.owner)
        assertEquals(setOf(other), fixture.controller.activeOwners())
        assertEquals("duty_lost", fixture.logs.last().field("reason"))
        fixture.controller.releaseAll()
        assertTrue(fixture.controller.activeOwners().isEmpty())
        assertEquals("service_destroyed", fixture.logs.last().field("reason"))
        assertTrue(fixture.locks.created.all { it.releaseCount == 1 })
    }

    @Test
    fun closingDeniesLaterAcquiresAndReleasesEveryHold() {
        val fixture = Fixture()
        fixture.controller.acquire(fixture.owner, WakePhase.SESSION)
        fixture.controller.close()
        assertEquals(WakeAcquireResult.Denied(WakeDenial.CLOSED), fixture.controller.acquire(fixture.owner, WakePhase.SESSION))
        assertTrue(fixture.locks.created.single().releaseCount == 1)
        assertEquals(listOf("acquire", "release", "close", "deny"), fixture.categories())
    }

    @Test
    fun leaseReleaseIsIdempotent() {
        val fixture = Fixture()
        val held = fixture.controller.acquire(fixture.owner, WakePhase.WORK) as WakeAcquireResult.Held
        held.lease.release()
        held.lease.release(WakeReleaseReason.FAILURE)
        held.lease.close()
        assertEquals(1, fixture.locks.created.single().releaseCount)
        assertEquals(1, fixture.categories().count { it == "release" })
    }

    @Test
    fun withSegmentReleasesOnSuccessAndOnFailure() = runBlocking {
        val fixture = Fixture()
        val value = fixture.controller.withSegment(fixture.owner, WakePhase.WORK) { 7 }
        assertEquals(WakeSegmentResult.Ran(7), value)
        assertTrue(fixture.controller.heldPhases(fixture.owner).isEmpty())
        val failure = runCatching {
            fixture.controller.withSegment(fixture.owner, WakePhase.WORK) { error("sensitive business failure") }
        }
        assertTrue(failure.isFailure)
        assertTrue(fixture.controller.heldPhases(fixture.owner).isEmpty())
        assertEquals("failure", fixture.logs.last().field("reason"))
        assertFalse(fixture.logs.toString().contains("sensitive business failure"))
    }

    @Test
    fun withSegmentReleasesOnCancellation() = runBlocking {
        val fixture = Fixture()
        val entered = CompletableDeferred<Unit>()
        val job = launch(Dispatchers.Default) {
            fixture.controller.withSegment(fixture.owner, WakePhase.WORK) {
                entered.complete(Unit)
                awaitCancellation()
            }
        }
        entered.await()
        assertEquals(setOf(WakePhase.WORK), fixture.controller.heldPhases(fixture.owner))
        job.cancelAndJoin()
        assertTrue(fixture.controller.heldPhases(fixture.owner).isEmpty())
        assertEquals("failure", fixture.logs.last().field("reason"))
    }

    @Test
    fun withSegmentOnADeniedBudgetDoesNotRunTheBlock() = runBlocking {
        val fixture = Fixture(limits = { WakeLockLimits(segmentMillis = 1_000, budgetMillis = 1_000) })
        val held = fixture.controller.acquire(fixture.owner, WakePhase.WORK) as WakeAcquireResult.Held
        fixture.clock.advance(1_000)
        held.lease.release()
        var ran = false
        val result = fixture.controller.withSegment(fixture.owner, WakePhase.WORK) { ran = true }
        assertEquals(WakeSegmentResult.Denied(WakeDenial.BUDGET_EXHAUSTED), result)
        assertFalse(ran)
    }

    @Test
    fun withSegmentReusingAnExistingHoldDoesNotReleaseIt() = runBlocking {
        val fixture = Fixture()
        val held = fixture.controller.acquire(fixture.owner, WakePhase.SESSION) as WakeAcquireResult.Held
        val result = fixture.controller.withSegment(fixture.owner, WakePhase.SESSION) { "written" }
        assertEquals(WakeSegmentResult.Ran("written"), result)
        assertEquals(setOf(WakePhase.SESSION), fixture.controller.heldPhases(fixture.owner))
        assertEquals(0, fixture.locks.created.single().releaseCount)
        held.lease.release()
    }

    @Test
    fun everyRecordHasTheSameFixedFieldsAndNoOwnerText() {
        val fixture = Fixture(limits = { WakeLockLimits(segmentMillis = 1_000, budgetMillis = 2_000) })
        val held = fixture.controller.acquire(fixture.owner, WakePhase.WORK) as WakeAcquireResult.Held
        fixture.controller.acquire(fixture.owner, WakePhase.WORK)
        fixture.clock.advance(250)
        held.lease.release()
        fixture.controller.release(fixture.owner)
        fixture.controller.close()
        assertEquals(
            setOf("owner", "category", "phase", "reason", "elapsedMs", "capMs", "remainingMs"),
            fixture.logs.flatMap { it.fields.keys() }.toSet(),
        )
        fixture.logs.forEach { record ->
            assertEquals("background.wakelock", record.event)
            assertEquals("platform.background", record.field("owner"))
            assertNull(record.taskId)
            assertNull(record.correlationId)
            assertFalse(record.fields.literal().contains(fixture.owner.id))
        }
    }

    @Test
    fun ownerCanBeBuiltFromBothDutyKinds() {
        val task = wakeOwner(ForegroundDuty(TaskId.parse("task-9"), 4, CorrelationId.parse("corr-9"), ForegroundPurpose.DATA_SYNC))
        assertEquals(WakeOwnerKind.TASK, task.kind)
        assertEquals("task-9.g4", task.id)
        val continuous = wakeOwner(ContinuousDuty("terminal-session-1", ForegroundPurpose.TERMINAL))
        assertEquals(WakeOwnerKind.CONTINUOUS, continuous.kind)
        assertEquals("terminal-session-1", continuous.id)
        try {
            WakeOwner(WakeOwnerKind.TASK, "bad owner")
            fail("owner with a space must be rejected")
        } catch (_: IllegalArgumentException) {
            // Owner strings are opaque ids, never free text.
        }
    }

    @Test
    fun limitsMustBePositiveAndSegmentWithinBudget() {
        val fixture = Fixture()
        assertTrue(fixture.controller.heldPhases(fixture.owner).isEmpty())
        try {
            WakeLockLimits(segmentMillis = 0, budgetMillis = 10)
            fail("zero segment must be rejected")
        } catch (_: IllegalArgumentException) {
            // Bounded segments are mandatory.
        }
        try {
            WakeLockLimits(segmentMillis = 20, budgetMillis = 10)
            fail("segment beyond budget must be rejected")
        } catch (_: IllegalArgumentException) {
            // A segment can never reserve more than the budget.
        }
    }

    private class Fixture(private val rejectAcquire: Boolean = false, limits: (WakePhase) -> WakeLockLimits = { WakeLockLimits() }) {
        val owner = WakeOwner(WakeOwnerKind.TASK, "task-1.g1")
        val clock = FakeClock()
        val logs = mutableListOf<LogRecord>()
        val locks = FakeFactory(rejectAcquire)
        private val logger = object : Logger {
            override fun log(record: LogRecord) {
                logs += record
            }
        }
        val controller = WakeLockController(locks, logger, limits, clock::now)

        fun categories(): List<String> = logs.map { requireNotNull(it.field("category")) }
    }

    private class FakeClock {
        private var millis = 1_000L

        fun now(): Long = millis

        fun advance(delta: Long) {
            millis += delta
        }
    }

    private class FakeFactory(private val rejectAcquire: Boolean) : WakeLockFactory {
        val created = mutableListOf<FakeHandle>()
        var acquireCount = 0

        override fun create(owner: WakeOwner, phase: WakePhase): WakeLockHandle {
            val handle = FakeHandle(rejectAcquire) { acquireCount++ }
            created += handle
            return handle
        }
    }

    private class FakeHandle(private val reject: Boolean, private val onAcquire: () -> Unit) : WakeLockHandle {
        val acquiredTimeouts = mutableListOf<Long>()
        var held = false
        var releaseCount = 0

        override val isHeld: Boolean get() = held

        override fun acquire(timeoutMillis: Long): Boolean {
            onAcquire()
            acquiredTimeouts += timeoutMillis
            if (reject) return false
            held = true
            return true
        }

        override fun release() {
            releaseCount++
            held = false
        }
    }
}

internal fun LogRecord.field(key: String): String? = (fields[key] as? JsonValue.Text)?.value
    ?: (fields[key] as? JsonValue.Number)?.toString()
    ?: (fields[key] as? JsonValue.Bool)?.value?.toString()

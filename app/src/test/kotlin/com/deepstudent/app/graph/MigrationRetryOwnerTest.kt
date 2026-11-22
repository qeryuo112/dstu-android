package com.deepstudent.app.graph

import com.deepstudent.platform.database.MigrationDiagnostic
import com.deepstudent.platform.database.MigrationFailure
import com.deepstudent.platform.database.MigrationStage
import com.deepstudent.platform.database.MigrationState
import com.deepstudent.platform.database.MigrationStatus
import com.deepstudent.platform.database.SharedDatabase
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MigrationRetryOwnerTest {
    @Test fun retrySurvivesObserverDisposalAndConcurrentClicksCoalesce() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val previous = failed()
        val ready = previous.copy(state = MigrationState.READY, stage = MigrationStage.READY, failure = null)
        val current = java.util.concurrent.atomic.AtomicReference(previous)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val owner = MigrationRetryOwner(scope, current::get) {
            calls.incrementAndGet()
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS))
            current.set(ready)
            ready
        }
        try {
            owner.start()
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            // A destroyed observer does not own the work; another screen can press concurrently.
            repeat(3) { owner.start() }
            assertEquals(1, calls.get())
            assertSame(previous, (owner.state.value as MigrationRetryState.Running).previous)
            release.countDown()
            val finished = withTimeout(10_000) { owner.state.first { it is MigrationRetryState.Finished } }
                as MigrationRetryState.Finished
            assertEquals(1, finished.generation)
            assertSame(ready, finished.result)
            assertEquals(1, calls.get())
            owner.start() // a stale button cannot retry an already-ready database
            assertEquals(1, calls.get())
        } finally {
            release.countDown()
            scope.cancel()
        }
    }

    @Test fun unexpectedRetryFailureLeavesThePreviousDiagnosticRetryable() = runBlocking {
        val previous = failed()
        val calls = AtomicInteger()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val owner = MigrationRetryOwner(scope, { previous }) {
            calls.incrementAndGet()
            throw IllegalStateException("private driver content")
        }
        try {
            owner.start()
            withTimeout(10_000) { owner.state.first { it is MigrationRetryState.Finished } }
            assertEquals(1, calls.get())
        } finally { scope.cancel() }
    }

    private fun failed(): MigrationStatus = MigrationStatus(
        SharedDatabase.VFS, MigrationState.FAILED, MigrationStage.MIGRATION, 0, 20261117,
        MigrationFailure(SharedDatabase.VFS, 20260101, MigrationDiagnostic.DATABASE_FAILED),
    )
}

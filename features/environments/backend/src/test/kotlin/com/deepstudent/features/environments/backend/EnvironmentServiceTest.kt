package com.deepstudent.features.environments.backend

import com.deepstudent.features.environments.api.EnvironmentCommandException
import com.deepstudent.features.environments.api.EnvironmentFailureCode
import com.deepstudent.features.environments.api.EnvironmentPhase
import com.deepstudent.features.environments.api.EnvironmentTaskId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EnvironmentServiceTest {
    @Test fun acceptedTaskIsBusyBeforeWorkerStartsAndConcurrentSubmissionIsRejected() = runTest {
        val f = EnvironmentFixture()
        val gate = CompletableDeferred<Unit>()
        f.effect = { if (it == EnvironmentCheckpoint.VERIFY) gate.await() }
        val service = EnvironmentService(f.coordinator(), null, this)
        service.installRootfs(testManifest, unreadSource)
        assertEquals(EnvironmentPhase.VERIFYING, service.state.value.phase)
        try {
            service.installRootfs(testManifest, unreadSource)
            fail("must reject overlapping mutation")
        } catch (failure: EnvironmentCommandException) {
            assertEquals(EnvironmentFailureCode.BUSY, failure.failure.code)
        }
        runCurrent()
        assertEquals(1, f.count(EnvironmentCheckpoint.VERIFY))
        gate.complete(Unit)
        runCurrent()
        assertEquals(EnvironmentPhase.READY, service.state.value.phase)
    }

    @Test fun immediateCompletionDoesNotLeaveAnOrphanJobOrBusyLease() = runTest {
        val f = EnvironmentFixture()
        val ownerScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            val service = EnvironmentService(f.coordinator(), null, ownerScope)
            val first = service.installRootfs(testManifest, unreadSource)
            assertEquals(EnvironmentPhase.READY, service.state.value.phase)
            val second = service.installRootfs(testManifest, unreadSource)
            assertNotEquals(first, second)
            assertEquals(EnvironmentPhase.READY, service.state.value.phase)
            assertNotEquals(f.generations.first(), f.generations.last())
        } finally { ownerScope.cancel() }
    }

    @Test fun cancellingStaleTaskDoesNotCancelCurrentTaskAndCancellationIsDurable() = runTest {
        val f = EnvironmentFixture()
        val gate = CompletableDeferred<Unit>()
        f.effect = { if (it == EnvironmentCheckpoint.EXTRACT) gate.await() }
        val service = EnvironmentService(f.coordinator(), null, this)
        val task = service.installRootfs(testManifest, unreadSource)
        runCurrent()
        service.cancel(EnvironmentTaskId("task-obsolete"))
        assertEquals(EnvironmentPhase.EXTRACTING, service.state.value.phase)
        service.cancel(task)
        assertEquals(EnvironmentPhase.CANCELLED, service.state.value.phase)
        assertEquals(EnvironmentFailureCode.CANCELLED, f.store.value?.failure?.code)
        gate.complete(Unit)
        service.retry()
        runCurrent()
        assertEquals(EnvironmentPhase.READY, service.state.value.phase)
        assertEquals(1, f.count(EnvironmentCheckpoint.VERIFY))
    }

    @Test fun restartWithPendingPackageAtDoneCheckpointDoesNotInventReady() = runTest {
        val interrupted = readyJournal().copy(pendingRecipeId = "git")
        val f = EnvironmentFixture(interrupted)
        val service = EnvironmentService(f.coordinator(), interrupted, this)
        assertEquals(EnvironmentPhase.FAILED, service.state.value.phase)
        assertEquals(EnvironmentFailureCode.INTERRUPTED, service.state.value.failure?.code)
        assertTrue(service.state.value.recoverable)
        service.retry()
        runCurrent()
        assertEquals(EnvironmentPhase.READY, service.state.value.phase)
        assertEquals(1, f.packageCalls)
        assertTrue(f.counts.isEmpty())
    }

    @Test fun failedIdFactoryDoesNotClaimTheMutationLease() = runTest {
        val f = EnvironmentFixture()
        var attempt = 0
        val service = EnvironmentService(f.coordinator(), null, this) {
            if (attempt++ == 0) "bad" else "task-valid-id"
        }
        try { service.installRootfs(testManifest, unreadSource); fail("invalid task id") }
        catch (_: IllegalArgumentException) { }
        service.installRootfs(testManifest, unreadSource)
        runCurrent()
        assertEquals(EnvironmentPhase.READY, service.state.value.phase)
    }

    @Test fun journalCommitFailureIsNonRecoverableAndDoesNotLeakRawException() = runTest {
        val f = EnvironmentFixture()
        f.store.reject = { true }
        val service = EnvironmentService(f.coordinator(), null, this)
        service.installRootfs(testManifest, unreadSource)
        runCurrent()
        assertEquals(EnvironmentFailureCode.CORRUPT_STATE, service.state.value.failure?.code)
        assertFalse(service.state.value.recoverable)
        assertFalse(service.state.value.failure!!.message.contains("private-file"))
        assertTrue(f.counts.isEmpty())
    }
    @Test fun reinstallDoesNotPublishThePreviousReceiptBeforeItsTaskCompletes() = runTest {
        val f = EnvironmentFixture(readyJournal())
        val gate = CompletableDeferred<Unit>()
        f.packageEffect = { gate.await() }
        val service = EnvironmentService(f.coordinator(), readyJournal(), this)
        service.installPackage("git")
        assertEquals(com.deepstudent.features.environments.api.PackageRecipeStatus.INSTALLING,
            service.state.value.recipes.first { it.id == "git" }.status)
        runCurrent()
        assertEquals(EnvironmentPhase.INSTALLING_PACKAGE, service.state.value.phase)
        gate.complete(Unit)
        runCurrent()
        assertEquals(EnvironmentPhase.READY, service.state.value.phase)
        // Receipt publication is also the mutation-slot release barrier.
        service.installPackage("git")
        runCurrent()
        assertEquals(EnvironmentPhase.READY, service.state.value.phase)
    }

}

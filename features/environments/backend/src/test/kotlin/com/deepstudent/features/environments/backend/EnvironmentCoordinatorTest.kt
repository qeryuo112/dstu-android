package com.deepstudent.features.environments.backend

import com.deepstudent.features.environments.api.EnvironmentCommandException
import com.deepstudent.features.environments.api.EnvironmentFailureCode
import com.deepstudent.features.environments.api.EnvironmentPhase
import com.deepstudent.features.environments.api.EnvironmentTaskId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class EnvironmentCoordinatorTest {
    @Test fun lowSpaceIsDurableBeforeReadingAndRetryReusesGeneration() = runBlocking {
        val f = EnvironmentFixture()
        f.available = testManifest.minimumFreeBytes - 1
        val failed = f.coordinator().installRootfs(task("low-space"), testManifest, unreadSource, f.update)
        assertEquals(EnvironmentFailureCode.LOW_SPACE, failed.failure?.code)
        assertEquals(EnvironmentCheckpoint.VERIFY, f.store.value?.checkpoint)
        assertTrue(f.counts.isEmpty())
        f.available = testManifest.minimumFreeBytes
        val ready = f.coordinator().retry(task("space-restored"), testManifest, null, f.update)
        assertEquals(failed.generationId, ready.generationId)
        assertEquals(ready.generationId, ready.activeGeneration)
        assertEquals(EnvironmentCheckpoint.DONE, ready.checkpoint)
        assertNull(ready.failure)
    }

    @Test fun cachedArchiveIsNotChargedAgainAtExtraction() = runBlocking {
        val f = EnvironmentFixture()
        f.effect = { stage -> if (stage == EnvironmentCheckpoint.VERIFY) {
            f.available = testManifest.minimumFreeBytes - testManifest.archiveBytes
        } }
        val ready = f.coordinator().installRootfs(task("space-budget"), testManifest, unreadSource, f.update)
        assertEquals(EnvironmentCheckpoint.DONE, ready.checkpoint)
    }

    @Test fun everySideEffectCutResumesFromItsDurableCheckpoint() = runBlocking {
        for (cut in EnvironmentCheckpoint.entries.filter { it != EnvironmentCheckpoint.DONE }) {
            val f = EnvironmentFixture()
            f.effect = { stage -> if (stage == cut) throw CancellationException("simulated cut") }
            try {
                f.coordinator().installRootfs(task("cut-${cut.name}"), testManifest, unreadSource, f.update)
                fail("expected interruption at $cut")
            } catch (_: CancellationException) { /* simulate a new owner */ }
            val interrupted = checkNotNull(f.store.value)
            assertEquals(cut, interrupted.checkpoint)
            assertEquals(EnvironmentPhase.CANCELLED, f.updates.last().second)
            assertEquals(EnvironmentFailureCode.CANCELLED, interrupted.failure?.code)
            f.effect = {}
            val ready = f.coordinator().retry(task("retry-${cut.name}"), testManifest, null, f.update)
            assertEquals(interrupted.generationId, ready.activeGeneration)
            EnvironmentCheckpoint.entries.filter { it < cut }.forEach { assertEquals(1, f.count(it)) }
            assertEquals(2, f.count(cut))
        }
    }

    @Test fun failedReplacementPreservesOldActiveAndReceiptsUntilSuccessfulSwitch() = runBlocking {
        val original = readyJournal()
        val f = EnvironmentFixture(original)
        f.healthy = false
        val failed = f.coordinator().installRootfs(task("replacement"), testManifest, unreadSource, f.update)
        assertEquals(EnvironmentFailureCode.HEALTH_CHECK_FAILED, failed.failure?.code)
        assertEquals(original.activeGeneration, failed.activeGeneration)
        assertEquals(original.receipts, failed.receipts)
        assertEquals(original.activeGeneration, f.active)
        assertEquals(0, f.count(EnvironmentCheckpoint.ACTIVATE))
        f.healthy = true
        val ready = f.coordinator().retry(task("replacement-retry"), testManifest, null, f.update)
        assertNotEquals(original.activeGeneration, ready.activeGeneration)
        assertEquals(failed.generationId, ready.activeGeneration)
        assertTrue(ready.receipts.isEmpty())
    }

    @Test fun retryRejectsDifferentManifestWithoutOverwritingJournal() = runBlocking {
        val f = EnvironmentFixture(readyJournal())
        val old = f.store.value
        try {
            f.coordinator().retry(task("wrong-manifest"), testManifest.copy(id = "another"), null, f.update)
            fail("must not trust checkpoint from another manifest")
        } catch (failure: EnvironmentCommandException) {
            assertEquals(EnvironmentFailureCode.CORRUPT_STATE, failure.failure.code)
        }
        assertSame(old, f.store.value)
        assertTrue(f.store.writes.isEmpty())
    }

    @Test fun wrongVerifiedTokenCannotReachExtraction() = runBlocking {
        val f = EnvironmentFixture().apply { archiveToken = "b".repeat(64) }
        val failed = f.coordinator().installRootfs(task("wrong-token"), testManifest, unreadSource, f.update)
        assertEquals(EnvironmentFailureCode.CORRUPT_STATE, failed.failure?.code)
        assertEquals(false, failed.failure?.recoverable)
        assertEquals(0, f.count(EnvironmentCheckpoint.EXTRACT))
    }

    @Test fun wrongStagingTokenCannotReachHealthOrActivation() = runBlocking {
        val f = EnvironmentFixture().apply { wrongStaging = true }
        val failed = f.coordinator().installRootfs(task("wrong-staging"), testManifest, unreadSource, f.update)
        assertEquals(EnvironmentFailureCode.CORRUPT_STATE, failed.failure?.code)
        assertEquals(0, f.count(EnvironmentCheckpoint.HEALTH))
        assertNull(f.active)
    }

    @Test fun activationBeforeFinalJournalCommitIsReconciledOnReentry() = runBlocking {
        val f = EnvironmentFixture()
        f.store.reject = { it.checkpoint == EnvironmentCheckpoint.DONE }
        try {
            f.coordinator().installRootfs(task("pointer-cut"), testManifest, unreadSource, f.update)
            fail("commit must fail closed")
        } catch (_: EnvironmentJournalWriteException) { }
        val interrupted = checkNotNull(f.store.value)
        assertEquals(EnvironmentCheckpoint.ACTIVATE, interrupted.checkpoint)
        assertEquals(interrupted.generationId, f.active)
        f.store.reject = { false }
        val ready = f.coordinator().retry(task("pointer-retry"), testManifest, null, f.update)
        assertEquals(EnvironmentCheckpoint.DONE, ready.checkpoint)
        assertEquals(1, f.count(EnvironmentCheckpoint.ACTIVATE))
        assertEquals(1, f.count(EnvironmentCheckpoint.EXTRACT))
    }

    @Test fun intentCommitFailureRunsNoEffectsAndDoesNotOverwriteOldJournal() = runBlocking {
        val original = readyJournal()
        val f = EnvironmentFixture(original)
        f.store.reject = { true }
        try {
            f.coordinator().installRootfs(task("intent-cut"), testManifest, unreadSource, f.update)
            fail("intent must be committed")
        } catch (_: EnvironmentJournalWriteException) { }
        assertSame(original, f.store.value)
        assertTrue(f.counts.isEmpty())
        assertTrue(f.updates.isEmpty())
    }

    private fun task(suffix: String) = EnvironmentTaskId("task-$suffix")
}

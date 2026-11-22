package com.deepstudent.features.environments.backend

import com.deepstudent.features.environments.api.EnvironmentFailureCode
import com.deepstudent.features.environments.api.EnvironmentPhase
import com.deepstudent.features.environments.api.EnvironmentTaskId
import com.deepstudent.features.environments.api.InstalledVersion
import com.deepstudent.features.environments.api.PackageReceipt
import com.deepstudent.features.environments.api.PackageRecipeStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PackageInstallationTest {
    @Test fun packageLowSpacePersistsIntentAndRetriesWithoutRootfsWork() = runBlocking {
        val f = EnvironmentFixture(readyJournal())
        f.available = PackageRecipeCatalog.Git.minimumFreeBytes - 1
        val failed = f.coordinator().installRecipe(task("low-space"), "git", f.update)
        assertEquals(EnvironmentFailureCode.LOW_SPACE, failed.failure?.code)
        assertEquals("git", f.store.value?.pendingRecipeId)
        assertEquals(0, f.packageCalls)
        assertEquals(readyJournal().receipts, failed.receipts)
        f.available++
        val ready = f.coordinator().retry(task("retry-space"), null, null, f.update)
        assertEquals(1, f.packageCalls)
        assertEquals(EnvironmentCheckpoint.DONE, ready.checkpoint)
        assertNull(ready.pendingRecipeId)
        assertTrue(f.counts.isEmpty())
    }

    @Test fun packageInterruptedAtDoneCheckpointRestoresAsInterruptedNotReady() = runBlocking {
        val f = EnvironmentFixture(readyJournal())
        f.packageEffect = { throw CancellationException("cut during apt") }
        try {
            f.coordinator().installRecipe(task("package-cut"), "git", f.update)
            fail("expected cancellation")
        } catch (_: CancellationException) { }
        val interrupted = checkNotNull(f.store.value)
        val restored = restoredEnvironmentSnapshot(interrupted.copy(failure = null))
        assertEquals(EnvironmentPhase.FAILED, restored.phase)
        assertEquals(EnvironmentFailureCode.INTERRUPTED, restored.failure?.code)
        assertEquals(PackageRecipeStatus.FAILED, restored.recipes.first { it.id == "git" }.status)
        assertEquals(readyJournal().receipts, interrupted.receipts)
        f.packageEffect = {}
        val ready = f.coordinator().retry(task("package-retry"), null, null, f.update)
        assertNull(ready.pendingRecipeId)
        assertEquals(2, f.packageCalls)
    }

    @Test fun emptyWrongIdentityDuplicateAndUnpinnedReceiptsNeverCommit() = runBlocking {
        val bad = listOf(
            gitReceipt().copy(versions = emptyList()),
            gitReceipt().copy(recipeId = "another"),
            gitReceipt().copy(versions = gitReceipt().versions + gitReceipt().versions),
            gitReceipt().copy(versions = listOf(InstalledVersion("git", "", "apt"))),
            gitReceipt().copy(versions = listOf(InstalledVersion("git", "2.4", "venv"))),
        )
        bad.forEachIndexed { index, receipt ->
            val original = readyJournal()
            val f = EnvironmentFixture(original).apply { this.receipt = receipt }
            val failed = f.coordinator().installRecipe(task("bad-receipt-$index"), "git", f.update)
            assertEquals(EnvironmentFailureCode.PACKAGE_INSTALL_FAILED, failed.failure?.code)
            assertEquals(original.receipts, failed.receipts)
            assertEquals("git", failed.pendingRecipeId)
        }
        val f = EnvironmentFixture(readyJournal()).apply {
            receipt = PackageReceipt("project-venv", testTime.toString(), listOf(
                InstalledVersion("python3-venv", "3.12", "apt"), InstalledVersion("python3-pip", "24", "apt"),
                InstalledVersion("python3", "3.12", "apt"), InstalledVersion("requests", "untrusted", "venv"),
            ))
        }
        val failed = f.coordinator().installRecipe(task("unpinned-venv"), "project-venv", f.update)
        assertEquals(EnvironmentFailureCode.PACKAGE_INSTALL_FAILED, failed.failure?.code)
    }

    @Test fun installingAndFailedStatusTakePrecedenceOverPreviousReceipt() {
        val receipts = mapOf("git" to gitReceipt())
        assertEquals(PackageRecipeStatus.INSTALLING,
            PackageRecipeCatalog.states(receipts, "git", true, false).first().status)
        assertEquals(PackageRecipeStatus.FAILED,
            PackageRecipeCatalog.states(receipts, "git", false, true).first().status)
    }

    @Test fun recipeCannotEscapeVenvOrInjectCommandArguments() {
        listOf("/root/../etc", "/root/projects/../../etc", "/root/a;evil", "/tmp/venv", "/root/..", "/root/a'bad").forEach { path ->
            assertInvalid { PackageRecipeCatalog.ProjectVenv.copy(venvPath = path) }
        }
        assertInvalid { PackageRecipeCatalog.Git.copy(aptPackages = listOf("git;id")) }
        assertInvalid { PackageRecipeCatalog.ProjectVenv.copy(pipRequirements = listOf(PipRequirement("requests", "2;id"))) }
    }

    private fun assertInvalid(block: () -> Unit) {
        try { block(); fail("must reject unsafe recipe") } catch (_: IllegalArgumentException) { }
    }
    private fun task(suffix: String) = EnvironmentTaskId("task-$suffix")
}

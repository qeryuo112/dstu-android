package com.deepstudent.services.backup.data

import com.deepstudent.platform.storage.DataSpacePhase
import com.deepstudent.platform.storage.DataSpacePhaseHook
import com.deepstudent.platform.storage.FileDataSpace
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

private class SimulatedDeath : Error()

class RestoreReentryTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun reentryAfterEveryExecutorCheckpointHasOneCompleteSlot() {
        val checkpoints = RestoreCheckpoint.entries
        for (point in checkpoints) {
            val root = temp.newFolder()
            val space = FileDataSpace(root)
            seed(space)
            var hit = false
            val killer = RestoreCheckpointHook { if (it == point && !hit) { hit = true; throw SimulatedDeath() } }
            try { executor(hook = killer).execute(space, "same-op", fixturePlan()) { MemoryArchive() }; fail("missed $point") }
            catch (_: SimulatedDeath) { }
            assertTrue(hit)
            val before = space.current("databases/vfs.db")
            assertTrue(before == "old-vfs" || before == "synthetic-new-vfs")
            assertEquals("old-vfs", java.io.File(root, "active/databases/vfs.db").readText())
            val resumed = executor().execute(FileDataSpace(root), "same-op", fixturePlan()) { MemoryArchive() }
            assertTrue("$point: $resumed", resumed is RestoreExecutionResult.Committed)
            assertEquals("synthetic-new-vfs", space.current("databases/vfs.db"))
            assertEquals("device-local", space.current("databases/runtime.db"))
            if (point == RestoreCheckpoint.ACTIVATION_RETURNED) {
                assertEquals(CommitDisposition.ALREADY_ACTIVE, (resumed as RestoreExecutionResult.Committed).disposition)
            }
        }
    }

    @Test fun reentryAcrossPointerCrashWindowsNeverRepeatsCommit() {
        val points = listOf(DataSpacePhase.STAGING_DURABLE, DataSpacePhase.POINTER_TEMP_DURABLE,
            DataSpacePhase.POINTER_REPLACED, DataSpacePhase.POINTER_DURABLE, DataSpacePhase.COMMIT_RECORDED)
        for (point in points) {
            val root = temp.newFolder()
            val initial = FileDataSpace(root)
            seed(initial)
            var hit = false
            val killer = FileDataSpace(root, phaseHook = DataSpacePhaseHook { phase ->
                if (phase == point && !hit) { hit = true; throw SimulatedDeath() }
            })
            try { executor().execute(killer, "same-op", fixturePlan()) { MemoryArchive() }; fail("missed $point") }
            catch (_: SimulatedDeath) { }
            assertTrue(hit)
            val resumed = executor().execute(FileDataSpace(root), "same-op", fixturePlan()) { MemoryArchive() }
            assertTrue("$point: $resumed", resumed is RestoreExecutionResult.Committed)
            assertEquals("synthetic-new-vfs", initial.current("databases/vfs.db"))
            assertEquals("old-vfs", java.io.File(root, "active/databases/vfs.db").readText())
        }
    }
    @Test fun pointerReplaceExceptionDoesNotClaimDurableCommit() {
        val root = temp.newFolder()
        seed(FileDataSpace(root))
        val space = FileDataSpace(root, phaseHook = DataSpacePhaseHook { phase ->
            if (phase == DataSpacePhase.POINTER_REPLACED) throw java.io.IOException("synthetic failure")
        })
        val result = executor().execute(space, "uncertain", fixturePlan()) { MemoryArchive() }
        assertTrue(result is RestoreExecutionResult.NeedsRepair)
        assertEquals("old-vfs", java.io.File(root, "active/databases/vfs.db").readText())
    }

}

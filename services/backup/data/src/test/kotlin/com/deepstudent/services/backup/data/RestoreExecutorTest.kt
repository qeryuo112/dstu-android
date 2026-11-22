package com.deepstudent.services.backup.data

import com.deepstudent.platform.storage.*
import com.deepstudent.services.backup.api.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RestoreExecutorTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun fullRestorePreservesUnmappedDomainsAndOldSlot() {
        val root = temp.newFolder()
        val space = FileDataSpace(root)
        seed(space)
        val external = File(root, "workspaces/out").apply { requireNotNull(parentFile).mkdirs(); writeText("outside") }
        val old = File(root, "active")
        val events = mutableListOf<RestoreEvent>()
        val verifier = object : RestoreCandidateVerifier {
            override fun verifyDatabases(candidate: File) {
                assertEquals("synthetic-new-vfs", File(candidate, "databases/vfs.db").readText())
                assertFalse(File(candidate, "databases/vfs.db-wal").exists())
                assertEquals("old-chat-wal", File(candidate, "databases/chat_v2.db-wal").readText())
                assertEquals("device-local-wal", File(candidate, "databases/runtime.db-wal").readText())
                assertFalse(File(candidate, "databases/chat_v2.db-shm").exists())
            }
            override fun verifyResources(candidate: File) {
                assertEquals("synthetic-blob", File(candidate, "vfs_blobs/aa/new").readText())
                assertEquals("old-unreferenced", File(candidate, "vfs_blobs/legacy/unreferenced").readText())
            }
        }
        val result = executor(verifier = verifier, audit = RestoreAudit(events::add)).execute(space, "op-1", fixturePlan()) { MemoryArchive() }
        assertEquals(RestoreExecutionResult.Committed("op-1", CommitDisposition.NEWLY_COMMITTED), result)
        assertEquals("old-vfs", File(old, "databases/vfs.db").readText())
        assertEquals("old-vfs-wal", File(old, "databases/vfs.db-wal").readText())
        assertEquals("outside", external.readText())
        assertEquals("device-attachment", space.current("chat_attachments/local"))
        assertEquals("old-chat", space.current("databases/chat_v2.db"))
        assertEquals("device-local", space.current("databases/runtime.db"))
        assertTrue(events.any { it.phase == RestorePhase.VERIFY_RESOURCES && it.status == RestoreStatus.SUCCEEDED })
        assertTrue(events.all { it.operationId == "op-1" && it.owner == "services.backup.restore" && it.elapsedMillis >= 0 })
    }

    @Test fun sameOperationNeverActivatesTwiceEvenAfterLaterRestore() {
        val space = FileDataSpace(temp.newFolder())
        seed(space)
        var opens = 0
        val executor = executor()
        fun run(id: String) = executor.execute(space, id, fixturePlan()) { opens++; MemoryArchive() }
        assertEquals(CommitDisposition.NEWLY_COMMITTED, (run("op-a") as RestoreExecutionResult.Committed).disposition)
        assertEquals(CommitDisposition.ALREADY_ACTIVE, (run("op-a") as RestoreExecutionResult.Committed).disposition)
        assertEquals(CommitDisposition.NEWLY_COMMITTED, (run("op-b") as RestoreExecutionResult.Committed).disposition)
        assertEquals(CommitDisposition.PREVIOUSLY_COMMITTED, (run("op-a") as RestoreExecutionResult.Committed).disposition)
        assertEquals(2, opens)
        assertEquals("synthetic-new-vfs", space.current("databases/vfs.db"))
    }

    @Test fun hashMismatchAndValidationFailureKeepOldSlot() {
        val space = FileDataSpace(temp.newFolder())
        seed(space)
        val corrupt = mapOf("manifest.json" to manifestBytes, "vfs.db" to "synthetic-WRONG-vfs".toByteArray(), "assets/vfs_blobs/aa/new" to blobBytes)
        val failed = executor().execute(space, "bad-hash", fixturePlan()) { MemoryArchive(corrupt) }
        assertTrue(failed is RestoreExecutionResult.Failed)
        assertEquals("old-vfs", space.current("databases/vfs.db"))
        val rejecting = object : RestoreCandidateVerifier {
            override fun verifyDatabases(candidate: File) = Unit
            override fun verifyResources(candidate: File) { error("resource details must never appear in the event") }
        }
        val events = mutableListOf<RestoreEvent>()
        val invalid = executor(verifier = rejecting, audit = RestoreAudit(events::add)).execute(space, "bad-resource", fixturePlan()) { MemoryArchive() }
        assertEquals(RestoreExecutionResult.Failed("bad-resource", RestorePhase.VERIFY_RESOURCES, RestoreError.RESOURCE_INVALID), invalid)
        assertEquals("old-vfs", space.current("databases/vfs.db"))
        assertFalse(events.toString().contains("resource details"))
    }

    @Test fun rejectsConflictingOrUntrustedTargetsAndArchiveLinks() {
        val space = FileDataSpace(temp.newFolder())
        seed(space)
        val plan = fixturePlan()
        val malicious = plan.copy(files = listOf(plan.files.first().copy(slotRelativePath = "../../other")))
        assertTrue(executor(malicious).execute(space, "bad-path", malicious) { MemoryArchive() } is RestoreExecutionResult.Failed)
        val localDb = plan.copy(files = listOf(plan.files.first().copy(slotRelativePath = "databases/runtime.db")))
        assertTrue(executor(localDb).execute(space, "bad-local", localDb) { MemoryArchive() } is RestoreExecutionResult.Failed)
        val link = MemoryArchive(extra = listOf(ArchiveEntry("vfs_blobs/link", ArchiveEntryKind.SYMBOLIC_LINK)))
        assertTrue(executor().execute(space, "bad-link", plan) { link } is RestoreExecutionResult.Failed)
        val duplicate = MemoryArchive(extra = listOf(ArchiveEntry("vfs.db", ArchiveEntryKind.FILE, 1)))
        assertTrue(executor().execute(space, "bad-duplicate", plan) { duplicate } is RestoreExecutionResult.Failed)
        assertEquals("old-vfs", space.current("databases/vfs.db"))
    }

    @Test fun activeLeaseMakesMaintenanceBusy() {
        val space = FileDataSpace(temp.newFolder())
        seed(space)
        space.acquireActive().use {
            assertEquals(RestoreExecutionResult.Busy, executor().execute(space, "busy", fixturePlan()) { MemoryArchive() })
        }
        assertEquals("old-vfs", space.current("databases/vfs.db"))
    }
    @Test fun partialPreviewNeverWritesStagingOrOpensArchive() {
        val space = FileDataSpace(temp.newFolder())
        seed(space)
        val partial = fixturePlan().copy(snapshotKind = SnapshotKind.PARTIAL_OVERLAY)
        val result = executor(partial).execute(space, "partial-preview", partial) { error("partial must not open archive") }
        assertTrue(result is RestoreExecutionResult.Failed)
        assertEquals("old-vfs", space.current("databases/vfs.db"))
    }

}

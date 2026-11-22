package com.deepstudent.platform.storage

import com.deepstudent.core.common.logging.LogRecord
import com.deepstudent.core.common.logging.Logger
import java.io.File
import java.nio.file.Files
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

abstract class DataSpaceSharedTests {
    protected abstract fun temporaryParent(): File
    private lateinit var root: File
    private val legacy get() = File(root, "active")
    private val control get() = File(root, ".dataspace")

    @Before fun createFixture() {
        root = File(temporaryParent(), "s209-case-${UUID.randomUUID()}")
        check(root.mkdirs())
        check(legacy.mkdirs())
        File(legacy, "old.txt").writeText("old-complete")
    }

    @After fun removeFixture() {
        root.deleteRecursively()
    }

    @Test fun originalRootIsNotMovedAndPointersSurviveRestart() {
        val store = FileDataSpace(root)
        store.acquireActive().use { assertEquals(legacy.canonicalFile, it.root.canonicalFile) }
        assertEquals("old-complete", File(legacy, "old.txt").readText())
        FileDataSpace(root).acquireActive().use { assertEquals("old-complete", File(it.root, "old.txt").readText()) }
        assertTrue(File(control, "initialized").isFile)
    }

    @Test fun localLeasesAndMaintenanceAreExclusiveAndCloseIsIdempotent() {
        val first = FileDataSpace(root).acquireActive()
        val secondStore = FileDataSpace(root)
        val second = secondStore.acquireActive()
        assertFailure(DataSpaceError.BUSY) { secondStore.withMaintenance { } }
        first.close()
        first.close()
        assertFailure(DataSpaceError.CLOSED) { first.root }
        assertFailure(DataSpaceError.BUSY) { secondStore.withMaintenance { } }
        second.close()
        secondStore.withMaintenance {
            assertFailure(DataSpaceError.BUSY) { secondStore.acquireActive() }
            assertFailure(DataSpaceError.BUSY) { secondStore.withMaintenance { } }
        }
        assertEquals(0, DataSpaceLocks.sizeForTest())
    }

    @Test fun activationAndReplayNeverOverwriteOldOrRewindNewerSlot() {
        val store = FileDataSpace(root)
        store.withMaintenance {
            val first = prepare("restore-1")
            File(first.root, "generation").writeText("one")
            assertEquals(ActivationResult.ACTIVATED, activate(first) { assertEquals("one", File(it, "generation").readText()) })
            assertEquals(StagingStatus.ACTIVE, first.status)
            assertFailure(DataSpaceError.CLOSED) { first.root }
        }
        store.withMaintenance {
            val first = prepare("restore-1")
            assertEquals(StagingStatus.ACTIVE, first.status)
            assertEquals(ActivationResult.ALREADY_ACTIVE, activate(first) { fail("should not validate active") })
            val second = prepare("restore-2")
            File(second.root, "generation").writeText("two")
            assertEquals(ActivationResult.ACTIVATED, activate(second) { assertEquals("two", File(it, "generation").readText()) })
            assertEquals(StagingStatus.PREVIOUSLY_ACTIVATED, first.status)
            assertEquals(ActivationResult.PREVIOUSLY_ACTIVATED, activate(first) { fail("should not rewind") })
            assertEquals("two", File(active, "generation").readText())
            assertFailure(DataSpaceError.FOREIGN_STAGING) { discard(first) }
        }
        assertEquals("old-complete", File(legacy, "old.txt").readText())
        store.acquireActive().use { assertEquals("two", File(it.root, "generation").readText()) }
    }

    @Test fun stageHandlesAreScopedAndCannotBeForgedOrReused() {
        val store = FileDataSpace(root)
        lateinit var stale: DataSpaceStaging
        store.withMaintenance { stale = prepare("one") }
        assertFailure(DataSpaceError.CLOSED) { stale.root }
        store.withMaintenance {
            assertFailure(DataSpaceError.FOREIGN_STAGING) { activate(stale) { } }
            val fake = object : DataSpaceStaging {
                override val status = StagingStatus.WRITABLE
                override val root = legacy
            }
            assertFailure(DataSpaceError.FOREIGN_STAGING) { activate(fake) { } }
            assertFailure(DataSpaceError.INVALID_OPERATION_ID) { prepare("../outside") }
            assertFailure(DataSpaceError.INVALID_OPERATION_ID) { prepare("x".repeat(129)) }
        }
    }

    @Test fun incompleteAndInvalidStagesCannotBecomeActive() {
        val store = FileDataSpace(root)
        store.withMaintenance {
            val stage = prepare("incomplete")
            assertFailure(DataSpaceError.INCOMPLETE_STAGING) { activate(stage) { } }
            File(stage.root, "new").writeText("secret-payload")
            assertFailure(DataSpaceError.VALIDATION_FAILED) { activate(stage) { error("private path and content") } }
            assertEquals("old-complete", File(active, "old.txt").readText())
        }
    }

    @Test fun stagingSymlinksAreRejectedButLegacyBusinessSymlinksDoNotBlockOpen() {
        val outside = File(root, "outside").apply { writeText("preserve") }
        val link = File(legacy, "link")
        Files.createSymbolicLink(link.toPath(), outside.toPath())
        val store = FileDataSpace(root)
        store.acquireActive().close()
        store.withMaintenance {
            val stage = prepare("symlink")
            Files.createSymbolicLink(File(stage.root, "escape").toPath(), outside.toPath())
            assertFailure(DataSpaceError.UNSAFE_ENTRY) { activate(stage) { } }
            assertFailure(DataSpaceError.UNSAFE_ENTRY) { discard(stage) }
            assertEquals("old-complete", File(active, "old.txt").readText())
        }
        assertEquals("preserve", outside.readText())
        Files.delete(link.toPath())
    }

    @Test fun unknownAndCorruptMetadataAreDiagnosedWithoutChangingOldData() {
        val store = FileDataSpace(root)
        store.acquireActive().close()
        val unexpected = File(control, "mystery").apply { writeText("evidence") }
        assertFailure(DataSpaceError.UNKNOWN_ENTRY) { store.acquireActive() }
        assertEquals("evidence", unexpected.readText())
        unexpected.delete()
        val pointer = File(control, "active")
        pointer.writeText("corrupt")
        assertFailure(DataSpaceError.CORRUPT_STATE) { store.acquireActive() }
        assertEquals("corrupt", pointer.readText())
        assertEquals("old-complete", File(legacy, "old.txt").readText())
    }

    @Test fun missingPointerAfterInitializationOrCrossSlotEvidenceFailsClosed() {
        val store = FileDataSpace(root)
        store.acquireActive().close()
        assertTrue(File(control, "active").delete())
        assertFailure(DataSpaceError.POINTER_MISSING) { store.acquireActive() }
        assertTrue(File(control, "initialized").delete())
        val other = File(control, "slots/slot-${"a".repeat(64)}")
        check(other.mkdir())
        assertFailure(DataSpaceError.POINTER_MISSING) { store.acquireActive() }
        assertTrue(other.isDirectory)
        assertEquals("old-complete", File(legacy, "old.txt").readText())
    }

    @Test fun interruptedFirstInitializationFallsBackOnlyToExistingLegacy() {
        val store = FileDataSpace(root)
        store.acquireActive().close()
        check(File(control, "active").delete())
        check(File(control, "initialized").delete())
        File(control, "active.tmp").writeText("untrusted partial pointer")
        store.acquireActive().use { assertEquals("old-complete", File(it.root, "old.txt").readText()) }
    }

    @Test fun discardCanBeRetriedAndDoesNotTouchOldRoot() {
        val store = FileDataSpace(root)
        store.withMaintenance {
            val stage = prepare("discard")
            File(stage.root, "partial").writeText("data")
            discard(stage)
            discard(stage)
            assertFailure(DataSpaceError.CLOSED) { stage.root }
            val retry = prepare("discard")
            assertTrue(checkNotNull(retry.root.list()).isEmpty())
            discard(retry)
        }
        assertEquals("old-complete", File(legacy, "old.txt").readText())
    }

    @Test fun everyCutpointLeavesExactlyAnOldOrNewCompleteRoot() {
        val phases = listOf(DataSpacePhase.STAGING_PREPARED, DataSpacePhase.STAGING_VALIDATED,
            DataSpacePhase.STAGING_DURABLE, DataSpacePhase.POINTER_TEMP_DURABLE, DataSpacePhase.POINTER_REPLACED,
            DataSpacePhase.POINTER_DURABLE, DataSpacePhase.COMMIT_RECORDED)
        phases.forEachIndexed { index, phase -> checkFault(index, phase) }
    }

    private fun checkFault(index: Int, phase: DataSpacePhase) {
        val dir = File(root, "case$index").apply { mkdir() }
        val initial = File(dir, "active").apply { mkdir() }
        File(initial, "version").writeText("old")
        val hook = DataSpacePhaseHook { if (it == phase) error("injected") }
        val failing = FileDataSpace(dir, phaseHook = hook)
        failing.acquireActive().close()
        assertFailure(DataSpaceError.IO_FAILURE) {
            failing.withMaintenance {
                val stage = prepare("op")
                File(stage.root, "version").writeText("new")
                activate(stage) { assertEquals("new", File(it, "version").readText()) }
            }
        }
        val expected = if (phase.ordinal >= DataSpacePhase.POINTER_REPLACED.ordinal) "new" else "old"
        FileDataSpace(dir).acquireActive().use { assertEquals(expected, File(it.root, "version").readText()) }
        assertEquals("old", File(initial, "version").readText())
        FileDataSpace(dir).withMaintenance {
            val retry = prepare("op")
            if (retry.status == StagingStatus.WRITABLE) File(retry.root, "version").writeText("new")
            val result = activate(retry) { assertEquals("new", File(it, "version").readText()) }
            assertTrue(result == ActivationResult.ACTIVATED || result == ActivationResult.ALREADY_ACTIVE)
        }
    }

    @Test fun logsAreStructuredCorrelatedAndContainNoPayloadOrPath() {
        val events = mutableListOf<DataSpaceEvent>()
        val records = mutableListOf<LogRecord>()
        val logger = object : Logger { override fun log(record: LogRecord) { records += record } }
        val store = FileDataSpace(root, logger = logger, audit = DataSpaceAudit { events += it })
        store.withMaintenance {
            val stage = prepare("private-operation-id")
            File(stage.root, "file").writeText("private-payload")
            activate(stage) { }
        }
        val activation = events.single { it.phase == DataSpacePhase.ACTIVATE && it.outcome == DataSpaceOutcome.STARTED }
        val phases = listOf(DataSpacePhase.STAGING_VALIDATED, DataSpacePhase.STAGING_DURABLE,
            DataSpacePhase.POINTER_TEMP_DURABLE, DataSpacePhase.POINTER_REPLACED,
            DataSpacePhase.POINTER_DURABLE, DataSpacePhase.COMMIT_RECORDED)
        phases.forEach { phase -> assertEquals(activation.correlationId, events.single { it.phase == phase }.correlationId) }
        assertTrue(events.all { it.owner == "platform.storage" && it.elapsedMillis >= 0 })
        val text = "$events $records"
        assertFalse(text.contains(root.absolutePath))
        assertFalse(text.contains("private-payload"))
        assertFalse(text.contains("private-operation-id"))
    }

    @Test fun malformedPhysicalEntryAndMissingActiveSlotDoNotTriggerGuessing() {
        val store = FileDataSpace(root)
        store.withMaintenance {
            val stage = prepare("one")
            File(stage.root, "new").writeText("data")
            activate(stage) { }
        }
        val slot = checkNotNull(File(control, "slots").listFiles()).single()
        val data = File(slot, "data")
        check(File(data, "new").delete())
        check(data.delete())
        assertFailure(DataSpaceError.CORRUPT_STATE) { store.acquireActive() }
        assertFalse(data.exists())
        assertEquals("old-complete", File(legacy, "old.txt").readText())
    }

    private fun assertFailure(error: DataSpaceError, block: () -> Unit) {
        try { block(); fail("expected $error") } catch (failure: DataSpaceException) { assertEquals(error, failure.error) }
    }
}

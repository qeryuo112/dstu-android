package com.deepstudent.app.backup

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.deepstudent.core.common.id.Digests
import com.deepstudent.services.backup.api.DomainAction
import com.deepstudent.services.backup.api.RestorePlan
import com.deepstudent.services.backup.api.RestoreProblemCode
import com.deepstudent.services.backup.api.SnapshotKind
import java.io.File
import java.util.zip.ZipFile
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** S2.12: frozen mobile ZIP bytes through real SAF, production queue and the cold restore boundary. */
@RunWith(AndroidJUnit4::class)
class LegacyMobileImportDeviceTest {
    @get:Rule val foreground = BackupForegroundRule()

    private val fixture = LegacyMobileFixtureSupport()

    @After fun clean() = fixture.close()

    @Test fun previewWithoutConfirmationDoesNotQueueOrSwitch(): Unit = runBlocking {
        val old = fixture.seedLocalState()
        val zip = fixture.original("standard")
        val plan = fixture.reader.read(fixture.uri(zip))
        assertLegacyPlan(zip, plan)
        val startup = BackupRestoreCoordinator(fixture.target)
        startup.beforeBusinessOpen()
        assertEquals(BackupRestoreUiStatus.IDLE, startup.status.value)
        assertFalse(queueFile("pending").exists())
        fixture.assertOldDataRemains(old, activeIsOld = true)
    }

    @Test fun standardOriginalImportActivatesAfterConfirmationAndColdValidation(): Unit = runBlocking {
        importOriginal("standard")
    }

    @Test fun continuityOriginalImportsDataWithoutSidecarIdentityTakeover(): Unit = runBlocking {
        importOriginal("continuity")
    }

    @Test fun replacingPreviewedInputWithAnotherValidZipIsRejected(): Unit = runBlocking {
        val old = fixture.seedLocalState()
        val source = fixture.original("standard")
        val confirmed = fixture.reader.read(fixture.uri(source))
        assertLegacyPlan(source, confirmed)
        val other = fixture.original("continuity")
        assertNotEquals(confirmed.manifestSha256, fixture.reader.read(fixture.uri(other)).manifestSha256)
        other.copyTo(source, overwrite = true)
        val coordinator = BackupRestoreCoordinator(fixture.target)
        coordinator.beforeBusinessOpen()
        coordinator.schedule(fixture.uri(source), confirmed)
        assertEquals(BackupRestoreUiStatus.PACK_CHANGED, coordinator.status.value)
        assertFalse(queueFile("pending").exists())
        BackupRestoreCoordinator(fixture.target).beforeBusinessOpen()
        fixture.assertOldDataRemains(old, activeIsOld = true)
    }

    @Test fun changedPayloadWithTheSameManifestIsRejectedBeforeQueueing(): Unit = runBlocking {
        val old = fixture.seedLocalState()
        val source = fixture.original("standard")
        val confirmed = fixture.reader.read(fixture.uri(source))
        assertLegacyPlan(source, confirmed)
        val altered = fixture.alterArchive(source, "wrong-digest.zip", corruptBlob = true)
        val rechecked = fixture.reader.read(fixture.uri(altered))
        assertEquals(confirmed.manifestSha256, rechecked.manifestSha256)
        assertFalse(rechecked.canStage)
        assertTrue(rechecked.problems.any { it.code == RestoreProblemCode.HASH_MISMATCH })
        val coordinator = BackupRestoreCoordinator(fixture.target)
        coordinator.beforeBusinessOpen()
        coordinator.schedule(fixture.uri(altered), confirmed)
        assertEquals(BackupRestoreUiStatus.PACK_CHANGED, coordinator.status.value)
        assertFalse(queueFile("pending").exists())
        fixture.assertOldDataRemains(old, activeIsOld = true)
    }

    @Test fun replacingTheConfirmedPrivateInputBeforeStartupDoesNotSwitch(): Unit = runBlocking {
        val old = fixture.seedLocalState()
        val source = fixture.original("standard")
        val plan = fixture.reader.read(fixture.uri(source))
        assertLegacyPlan(source, plan)
        val coordinator = queue(source, plan)
        assertEquals(BackupRestoreUiStatus.QUEUED, coordinator.status.value)
        fixture.assertOldDataRemains(old, activeIsOld = true)
        fixture.original("continuity").copyTo(queueFile("confirmed.zip"), overwrite = true)
        val startup = BackupRestoreCoordinator(fixture.target)
        startup.beforeBusinessOpen()
        assertEquals(BackupRestoreUiStatus.PACK_CHANGED, startup.status.value)
        fixture.assertOldDataRemains(old, activeIsOld = true)
    }

    @Test fun declaredSchemaVersionMustMatchTheActualSourceHistory(): Unit = runBlocking {
        val old = fixture.seedLocalState()
        val source = fixture.original("standard")
        val altered = fixture.alterArchive(source, "wrong-source-version.zip", sourceVersion = 20261115L)
        val plan = fixture.reader.read(fixture.uri(altered))
        assertTrue(plan.problems.toString(), plan.canStage)
        fixture.assertSourceBinding(altered, plan)
        assertEquals(20261115L, checkNotNull(plan.legacyImport).schemaVersions["vfs"])
        assertEquals(BackupRestoreUiStatus.QUEUED, queue(altered, plan).status.value)
        fixture.assertOldDataRemains(old, activeIsOld = true)
        val startup = BackupRestoreCoordinator(fixture.target)
        startup.beforeBusinessOpen()
        assertEquals(BackupRestoreUiStatus.FAILED, startup.status.value)
        fixture.assertOldDataRemains(old, activeIsOld = true)
    }

    @Test fun missingArchiveBlobIsNotSatisfiedByAnIdenticalBlobInTheOldSlot(): Unit = runBlocking {
        val old = fixture.seedLocalState()
        val source = fixture.original("standard")
        val blobPath = fixture.seeds.getString("blobRelativePath")
        val oldBlob = old.root.resolve("vfs_blobs/$blobPath")
        oldBlob.parentFile!!.mkdirs()
        ZipFile(source).use { zip ->
            zip.getInputStream(checkNotNull(zip.getEntry("assets/vfs_blobs/$blobPath"))).use { input ->
                oldBlob.outputStream().use { output -> input.copyTo(output) }
            }
        }
        assertEquals(fixture.seeds.getString("blobHash"), Digests.sha256Hex(oldBlob.readBytes()))
        val altered = fixture.alterArchive(source, "missing-referenced-blob.zip", omitBlob = true)
        val plan = fixture.reader.read(fixture.uri(altered))
        assertTrue(plan.problems.toString(), plan.canStage)
        fixture.assertSourceBinding(altered, plan)
        assertFalse(plan.files.any { it.archivePath == "assets/vfs_blobs/$blobPath" })
        assertEquals(BackupRestoreUiStatus.QUEUED, queue(altered, plan).status.value)
        fixture.assertOldDataRemains(old, activeIsOld = true)
        val startup = BackupRestoreCoordinator(fixture.target)
        startup.beforeBusinessOpen()
        assertEquals(BackupRestoreUiStatus.FAILED, startup.status.value)
        fixture.assertOldDataRemains(old, activeIsOld = true)
        assertEquals(fixture.seeds.getString("blobHash"), Digests.sha256Hex(oldBlob.readBytes()))
    }

    private suspend fun importOriginal(kind: String) {
        val old = fixture.seedLocalState()
        val source = fixture.original(kind)
        if (kind == "continuity") {
            val sidecar = fixture.copySidecarAndCheckFrozenDigest()
            ZipFile(source).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) assertFalse(entries.nextElement().name.endsWith(".continuity.json"))
                assertNull(zip.getEntry(sidecar.name))
            }
        }
        val plan = fixture.reader.read(fixture.uri(source))
        assertLegacyPlan(source, plan)
        val coordinator = queue(source, plan)
        assertEquals(BackupRestoreUiStatus.QUEUED, coordinator.status.value)
        assertArrayEquals(source.readBytes(), queueFile("confirmed.zip").readBytes())
        assertEquals(plan.manifestSha256, PendingBackupRestore(queueFile(".")).request()?.manifestHash)
        fixture.assertOldDataRemains(old, activeIsOld = true)
        assertFalse(old.root.resolve("legacy-import/audit.db").exists())
        val startup = BackupRestoreCoordinator(fixture.target)
        startup.beforeBusinessOpen()
        assertEquals(BackupRestoreUiStatus.APPLIED, startup.status.value)
        LegacyMobileReadbackAssertions(fixture.target, fixture.scratch, fixture.seeds).verify(source, old)
        fixture.assertOldDataRemains(old, activeIsOld = false)
        assertFalse(queueFile("pending").exists())
        assertFalse(queueFile("confirmed.zip").exists())
        val repeatedStartup = BackupRestoreCoordinator(fixture.target)
        repeatedStartup.beforeBusinessOpen()
        assertEquals(BackupRestoreUiStatus.APPLIED, repeatedStartup.status.value)
    }

    private fun assertLegacyPlan(source: File, plan: RestorePlan) {
        assertEquals(SnapshotKind.FULL, plan.snapshotKind)
        assertEquals(21, plan.domains.size)
        assertEquals(4, plan.domains.count { it.id.startsWith("database:") && it.action == DomainAction.STAGE_IN_SLOT })
        assertEquals(DomainAction.PRESERVE_INACTIVE, plan.domains.single { it.id == "audit" }.action)
        assertTrue(plan.problems.toString(), plan.canStage)
        assertFalse(plan.canSwitchSlot)
        val report = checkNotNull(plan.legacyImport)
        assertEquals("3.0.0", report.manifestVersion)
        assertEquals("0.20.0", report.appVersion)
        assertEquals(mapOf("vfs" to 20261111L, "llm_usage" to 20260826L, "chat_v2" to 20261111L,
            "mistakes" to 20261005L), report.schemaVersions)
        val audit = plan.files.single { it.archivePath == "databases/audit.db" }
        assertEquals("legacy-import/audit.db", audit.slotRelativePath)
        fixture.assertSourceBinding(source, plan)
    }

    private suspend fun queue(source: File, plan: RestorePlan): BackupRestoreCoordinator =
        BackupRestoreCoordinator(fixture.target).also { coordinator ->
            coordinator.beforeBusinessOpen()
            coordinator.schedule(fixture.uri(source), plan)
        }

    private fun queueFile(name: String) = fixture.target.noBackupFilesDir.resolve("backup-restore").resolve(name)
}

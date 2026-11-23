package com.deepstudent.app.backup

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.deepstudent.app.storage.AndroidOperationLogger
import com.deepstudent.app.storage.MainStorageResources
import com.deepstudent.platform.security.AndroidKeystoreKeyWrapper
import com.deepstudent.platform.security.SecurityFactory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RestoreBaselineDeviceTest {
    @get:Rule val foreground = BackupForegroundRule()

    @get:Rule val temp = remediationTemporaryFolder()

    @Test fun allFourDatabasesResetOnlyInTheCandidateAndKeepNewChangesOnReopen() {
        RestoreBaselineCases(temp.root, ::androidRestoreFixture).fourDatabaseRestore()
    }

    @Test fun lastDatabaseFailureKeepsTheActiveSlotAndCanBeRetried() {
        RestoreBaselineCases(temp.root, ::androidRestoreFixture).failedRestoreCanRetry()
    }

    @Test fun coldRestoreRepairsWriterAndSyncsNewChangesWithFrozenDesktop(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val endpoint = checkNotNull(args.getString("s216_ENDPOINT"))
        check(endpoint.matches(Regex("http://127\\.0\\.0\\.1:[0-9]{1,5}/dav/"))) { "Only the dedicated loopback fixture is allowed" }
        val alias = "fixture.s216.${UUID.randomUUID()}"
        LegacyMobileFixtureSupport().use { fixture ->
            val old = fixture.seedLocalState()
            val stateRoot = fixture.target.noBackupFilesDir.canonicalFile.resolve("sync")
            val openSecurity = { SecurityFactory.create(fixture.scratch.canonicalFile.resolve("security"), alias) }
            val identity = OrdinaryRestoreIdentity(fixture.target, openSecurity)
            try {
                RestoreSyncScenario(androidRestoreFixture(temp.root), temp.root, remediationCorpus(), AndroidOperationLogger) { name ->
                    checkNotNull(args.getString("s216_$name")) { "Missing fixture argument $name" }
                }.verify(stateRoot, openSecurity) { zip ->
                    val source = fixture.uri(zip.copyTo(fixture.scratch.resolve("incoming.zip")))
                    val originalId = stateRoot.resolve("device-id").readText()
                    val coordinator = BackupRestoreCoordinator(fixture.target, identity).also { it.beforeBusinessOpen() }
                    val plan = fixture.reader.read(source)
                    assertTrue(plan.problems.toString(), plan.canStage)
                    val scheduled = coordinator.schedule(source, plan)
                    assertTrue("ordinary queue status=${coordinator.status.value}", scheduled)
                    assertEquals(originalId, stateRoot.resolve("device-id").readText())
                    assertEquals(BackupRestoreUiStatus.QUEUED, coordinator.status.value)
                    val queue = PendingBackupRestore(fixture.target.noBackupFilesDir.resolve("backup-restore"))
                    val operation = checkNotNull(queue.request()).id
                    val cold = BackupRestoreCoordinator(fixture.target, identity).also { it.beforeBusinessOpen() }
                    assertEquals(BackupRestoreUiStatus.APPLIED_REPAIR_SYNC, cold.status.value)
                    val restarted = BackupRestoreCoordinator(fixture.target, identity).also { it.beforeBusinessOpen() }
                    assertEquals(BackupRestoreUiStatus.APPLIED_REPAIR_SYNC, restarted.status.value)
                    assertEquals("local-only", old.root.resolve("local-state.txt").readText())
                    MainStorageResources.open(fixture.target).use { storage ->
                        assertNotEquals(old.root, storage.activeRoot)
                        RestoreSyncScenario.RestoredSlot(storage.activeRoot, operation)
                    }
                }
            } finally {
                AndroidKeystoreKeyWrapper(alias).deleteKey()
            }
        }
    }
}

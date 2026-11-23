package com.deepstudent.app.backup

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.deepstudent.app.storage.MainStorageResources
import com.deepstudent.app.sync.DatabaseSyncBlobs
import com.deepstudent.core.common.id.Digests
import com.deepstudent.platform.security.AndroidKeystoreKeyWrapper
import com.deepstudent.platform.security.SecurityFactory
import com.deepstudent.services.backup.api.RestoreAdmission
import com.deepstudent.services.resources.data.SyncBlobStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RestoreBlobReferencesDeviceTest {
    @get:Rule val foreground = BackupForegroundRule()

    @get:Rule val temp = remediationTemporaryFolder()
    private fun cases() = RestoreBlobCases(temp.root, ::androidRestoreFixture)

    @Test fun missingZeroCountUnreferencedBlobDoesNotBlockFullRestore() {
        cases().missingZeroCountUnreferencedBlobDoesNotBlockFullRestore()
    }

    @Test fun intactArchiveAttachmentIsInstalledEvenWhenCounterIsStale() {
        cases().intactArchiveAttachmentIsInstalledEvenWhenCounterIsStale()
    }

    @Test fun countDirectExternalAndCompressedPreviewReferencesPreventMissingPayload() {
        cases().countDirectExternalAndCompressedPreviewReferencesPreventMissingPayload()
    }

    @Test fun oldSlotBytesCannotStandInForAnOmittedArchiveAttachment() = cases().oldSlotBytesCannotStandInForAnOmittedArchiveAttachment()

    @Test fun absentBlobMetadataAndInconsistentArchivedHashOrSizeAreRejected() {
        cases().absentBlobMetadataAndInconsistentArchivedHashOrSizeAreRejected()
    }

    @Test fun frozenDesktopExportAuthenticatesAndRestoresNotesAndAttachmentsButNotOrphanBytes(): Unit = runBlocking {
        val alias = "fixture.s217.${UUID.randomUUID()}"
        val password = "public-s217-fixture".toCharArray()
        LegacyMobileFixtureSupport().use { fixture ->
            try {
                val old = fixture.seedLocalState()
                val source = remediationCorpus().resolve("desktop-orphan.zip").copyTo(fixture.scratch.resolve("orphan.zip"))
                SecurityFactory.create(fixture.scratch.canonicalFile.resolve("security"), alias).use { security ->
                    val restore = BackupRestoreCoordinator(fixture.target).also { it.beforeBusinessOpen() }
                    val actions = SafEncryptedBackupActions(
                        fixture.target,
                        EncryptedBackupScratch(fixture.target.cacheDir),
                        restore,
                    ) { security }
                    actions.prepare(fixture.uri(source), EncryptedBackupIntent.IMPORT, password).use { result ->
                        val plan = checkNotNull(result.plan)
                        assertTrue(plan.canStage)
                        assertEquals(RestoreAdmission.AUTHENTICATED_PACKAGE, plan.source.admission)
                        fixture.assertSourceBinding(result.artifact, plan)
                        assertTrue(actions.schedule(result))
                        fixture.assertOldDataRemains(old, activeIsOld = true)
                    }
                    val startup = BackupRestoreCoordinator(fixture.target).also { it.beforeBusinessOpen() }
                    assertEquals(BackupRestoreUiStatus.APPLIED, startup.status.value)
                    fixture.assertOldDataRemains(old, activeIsOld = false)
                    MainStorageResources.open(fixture.target).use { storage ->
                        val db = storage.databaseForSync()
                        val orphan = db.query(
                            "SELECT hash,relative_path FROM blobs WHERE mime_type='application/x-dstu-orphan-fixture' AND ref_count=0",
                        ) {
                            it.text(0) to it.text(1)
                        }.single()
                        val blobs = storage.activeRoot.resolve("vfs_blobs")
                        assertFalse(blobs.resolve(orphan.second).exists())
                        assertTrue(DatabaseSyncBlobs(db, SyncBlobStore(blobs)).list().none { it.hash == orphan.first })
                        val seeds = fixture.seeds
                        val ids = seeds.getJSONArray("noteIds")
                        val contents = seeds.getJSONArray("noteContents")
                        for (i in 0 until ids.length()) assertEquals(contents.getString(i), storage.notes.get(ids.getString(i))?.content)
                        val expectedBlob = blobs.resolve(seeds.getString("blobRelativePath"))
                        assertEquals(seeds.getString("fileSha256"), Digests.sha256Hex(expectedBlob.readBytes()))
                    }
                }
            } finally {
                password.fill('\u0000')
                AndroidKeystoreKeyWrapper(alias).deleteKey()
            }
        }
    }
}

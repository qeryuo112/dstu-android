package com.deepstudent.app.backup

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.deepstudent.app.storage.MainStorageResources
import com.deepstudent.core.common.id.Digests
import com.deepstudent.platform.security.SecurityFactory
import com.deepstudent.services.backup.api.RestoreAdmission
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Host provides frozen-desktop exports; device uses real SAF ports and cold restore on isolated roots. */
@RunWith(AndroidJUnit4::class)
class EncryptedBackupInteropDeviceTest {
    @get:Rule val foreground = BackupForegroundRule()
    private val fixture = LegacyMobileFixtureSupport()
    private val corpus = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir.canonicalFile.resolve("s214-interop")
    private val security = SecurityFactory.create(fixture.scratch.canonicalFile.resolve("security"), "fixture.s214.${UUID.randomUUID()}")
    private val restore = BackupRestoreCoordinator(fixture.target).also { it.beforeBusinessOpen() }
    private val actions = SafEncryptedBackupActions(fixture.target, EncryptedBackupScratch(fixture.target.cacheDir), restore) { security }
    private val password = "fixture-password".toCharArray()

    @After fun clean() {
        password.fill('\u0000')
        security.close()
        fixture.close()
    }

    @Test fun desktopPortableV2AuthenticatesAndRestoresRealNotesAndAttachments(): Unit = runBlocking {
        checkRestore("desktop-portable-v2.zip")
    }

    @Test fun desktopPortableV1AuthenticatesAndUsesTheSameColdPath(): Unit = runBlocking {
        checkRestore("desktop-portable-v1-converted.zip")
    }

    @Test fun desktopPortableV1ConvertedToV2RestoresTheSameData(): Unit = runBlocking {
        checkRestore("desktop-portable-v1-to-v2.zip")
    }

    @Test fun desktopV2WrongPasswordDoesNotScheduleOrTouchTheSlot(): Unit = runBlocking {
        val before = fixture.seedLocalState()
        val source = localSource("desktop-portable-v2.zip")
        val wrong = "wrong-fixture-password".toCharArray()
        try {
            val error = try {
                actions.prepare(fixture.uri(source), EncryptedBackupIntent.IMPORT, wrong).close()
                error("wrong password was accepted")
            } catch (error: PortableFileException) { error }
            assertEquals(PortableFileError.AUTHENTICATION_FAILED, error.error)
            assertEquals(BackupRestoreUiStatus.IDLE, restore.status.value)
            assertFalse(fixture.target.noBackupFilesDir.resolve("backup-restore/pending").exists())
            fixture.assertOldDataRemains(before, activeIsOld = true)
        } finally { wrong.fill('\u0000') }
    }

    @Test fun androidPortableV2ExportsThroughResolverForDesktopImport(): Unit = runBlocking {
        val source = localSource("controlled-full.zip")
        val before = fileHash(source)
        actions.prepare(fixture.uri(source), EncryptedBackupIntent.EXPORT_PORTABLE, password).use { result ->
            val document = fixture.scratch.resolve("saved-portable.zip")
            actions.export(result, fixture.uri(document))
            assertTrue(document.isFile)
            assertEquals(fileHash(result.artifact), fileHash(document))
            assertEquals(before, fileHash(source))
            document.copyTo(corpus.resolve("android-export.zip"), overwrite = false)
        }
    }

    private suspend fun checkRestore(name: String) {
        val source = localSource(name)
        val before = fileHash(source)
        val old = fixture.seedLocalState()
        actions.prepare(fixture.uri(source), EncryptedBackupIntent.IMPORT, password).use { result ->
            val plan = checkNotNull(result.plan)
            assertTrue(plan.problems.toString(), plan.canStage)
            assertEquals(RestoreAdmission.AUTHENTICATED_PACKAGE, plan.source.admission)
            fixture.assertSourceBinding(result.artifact, plan)
            assertTrue(actions.schedule(result))
            assertEquals(BackupRestoreUiStatus.QUEUED, restore.status.value)
            val request = PendingBackupRestore(fixture.target.noBackupFilesDir.resolve("backup-restore")).request()
            assertEquals(result.trace.operationId.toString(), request?.id)
            fixture.assertOldDataRemains(old, activeIsOld = true)
        }
        val startup = BackupRestoreCoordinator(fixture.target).also { it.beforeBusinessOpen() }
        assertEquals(BackupRestoreUiStatus.APPLIED, startup.status.value)
        fixture.assertOldDataRemains(old, activeIsOld = false)
        verifyContent()
        assertEquals(before, fileHash(source))
    }

    private suspend fun verifyContent() {
        val seeds = fixture.seeds
        MainStorageResources.open(fixture.target).use { storage ->
            val ids = seeds.getJSONArray("noteIds")
            val contents = seeds.getJSONArray("noteContents")
            for (i in 0 until ids.length()) assertEquals(contents.getString(i), storage.notes.get(ids.getString(i))?.content)
            val file = storage.activeRoot.resolve("vfs_blobs/${seeds.getString("blobRelativePath")}")
            assertEquals(seeds.getString("fileSha256"), fileHash(file))
        }
    }

    private fun localSource(name: String): File = corpus.resolve(name).copyTo(fixture.scratch.resolve(name))
    private fun fileHash(file: File): String = Digests.sha256Hex(file.readBytes())
}

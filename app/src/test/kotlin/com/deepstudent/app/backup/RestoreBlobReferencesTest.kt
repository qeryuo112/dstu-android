package com.deepstudent.app.backup

import com.deepstudent.platform.database.SharedDatabase
import com.deepstudent.platform.security.DsbkCrypto
import com.deepstudent.platform.security.DsbkStagingArea
import com.deepstudent.services.backup.api.RestoreAdmission
import com.deepstudent.services.backup.backend.ArchivePreflight
import com.deepstudent.services.backup.data.CommitDisposition
import com.deepstudent.services.backup.data.RestoreExecutionResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RestoreBlobReferencesTest {
    @get:Rule val temp = TemporaryFolder()

    private fun cases() = RestoreBlobCases(temp.root, ::hostRestoreFixture)

    @Test fun missingZeroCountUnreferencedBlobDoesNotBlockFullRestore() {
        cases().missingZeroCountUnreferencedBlobDoesNotBlockFullRestore()
        System.getenv("DSTU_S217_DESKTOP_FIXTURE")?.let { verifyDesktopExport(File(it)) }
    }

    @Test fun intactArchiveAttachmentIsInstalledEvenWhenCounterIsStale() {
        cases().intactArchiveAttachmentIsInstalledEvenWhenCounterIsStale()
    }

    @Test fun countDirectExternalAndCompressedPreviewReferencesPreventMissingPayload() {
        cases().countDirectExternalAndCompressedPreviewReferencesPreventMissingPayload()
    }

    @Test fun oldSlotBytesCannotStandInForAnOmittedArchiveAttachment() {
        cases().oldSlotBytesCannotStandInForAnOmittedArchiveAttachment()
    }

    @Test fun absentBlobMetadataAndInconsistentArchivedHashOrSizeAreRejected() {
        cases().absentBlobMetadataAndInconsistentArchivedHashOrSizeAreRejected()
    }

    private fun verifyDesktopExport(input: File) {
        val host = hostRestoreFixture(temp.newFolder("desktop"))
        val directory = temp.newFolder("portable")
        val source = input.copyTo(directory.resolve("portable.zip"))
        val output = directory.resolve("unsealed.zip")
        val password = "public-s217-fixture".toCharArray()
        val budget = 32L * 1024 * 1024
        try {
            DsbkStagingArea(directory.resolve("staging"), budget).use { staging ->
                val portable = PortableBackupFiles(directory, staging, DsbkCrypto.production(), budget, budget, budget)
                portable.importPortableZip(
                    source,
                    output,
                    password,
                    PortableArchiveVerifier { archive, cancel ->
                        check(ArchivePreflight.inspectAuthenticated(archive, budget, cancel).plan.canStage)
                    },
                )
            }
        } finally {
            password.fill('\u0000')
        }
        assertEquals(
            RestoreExecutionResult.Committed("host-restore", CommitDisposition.NEWLY_COMMITTED),
            host.restore(output, admission = RestoreAdmission.AUTHENTICATED_PACKAGE),
        )
        host.open(host.active, SharedDatabase.VFS).use { db ->
            val orphan = db.query("SELECT relative_path FROM blobs WHERE mime_type='application/x-dstu-orphan-fixture' AND ref_count=0") {
                it.text(0)
            }.single()
            assertFalse(host.active.resolve("vfs_blobs/$orphan").exists())
            assertTrue(db.query("SELECT count(*) FROM notes") { it.long(0) }.single() > 0)
        }
    }
}

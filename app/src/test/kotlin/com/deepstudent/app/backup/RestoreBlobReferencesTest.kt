package com.deepstudent.app.backup

import com.deepstudent.app.sync.DatabaseSyncBlobs
import com.deepstudent.core.common.id.Digests
import com.deepstudent.platform.database.SharedDatabase
import com.deepstudent.platform.database.SqlDatabase
import com.deepstudent.platform.security.DsbkCrypto
import com.deepstudent.platform.security.DsbkStagingArea
import com.deepstudent.services.backup.api.RestoreAdmission
import com.deepstudent.services.backup.backend.ArchivePreflight
import com.deepstudent.services.backup.data.CommitDisposition
import com.deepstudent.services.backup.data.RestoreError
import com.deepstudent.services.backup.data.RestoreExecutionResult
import com.deepstudent.services.backup.data.RestorePhase
import com.deepstudent.services.resources.data.SyncBlobStore
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RestoreBlobReferencesTest {
    @get:Rule val temp = TemporaryFolder()
    private val payload = "attachment payload".toByteArray()
    private val hash = Digests.sha256Hex(payload)
    private val relative = "${hash.take(2)}/$hash.bin"

    @Test fun missingZeroCountUnreferencedBlobDoesNotBlockFullRestore() {
        val host = fixture("orphan")
        host.incoming(SharedDatabase.VFS) { addBlob(it) }
        assertCommitted(host.restore(host.archive()))
        assertFalse(host.active.resolve("vfs_blobs/$relative").exists())
        host.open(host.active, SharedDatabase.VFS).use { db ->
            assertEquals(listOf(hash), db.query("SELECT hash FROM blobs WHERE ref_count=0") { it.text(0) })
            val sync = DatabaseSyncBlobs(db, SyncBlobStore(host.active.resolve("vfs_blobs")))
            assertTrue(sync.list().isEmpty())
            addFile(db, preview = """{"pages":[{"blob_hash":"$hash"}]}""")
            assertEquals(hash, sync.list().single().hash)
        }
        System.getenv("DSTU_S217_DESKTOP_FIXTURE")?.let { verifyDesktopExport(File(it)) }
    }

    @Test fun intactArchiveAttachmentIsInstalledEvenWhenCounterIsStale() {
        val host = fixture("present")
        host.incoming(SharedDatabase.VFS) { db -> addBlob(db); addFile(db, blob = "'$hash'") }
        assertCommitted(host.restore(host.archive(mapOf(relative to payload))))
        assertArrayEquals(payload, host.active.resolve("vfs_blobs/$relative").readBytes())
        assertEquals("old slot", host.original.resolve("old-note").readText())
    }

    @Test fun countDirectExternalAndCompressedPreviewReferencesPreventMissingPayload() {
        val references = listOf<(SqlDatabase) -> Unit>(
            { it.execute("UPDATE blobs SET ref_count=1") },
            { addFile(it, blob = "'$hash'", deleted = "'tombstone'") },
            { addFile(it, compressed = "'$hash'") },
            { addFile(it, preview = """{"pages":[{"blob_hash":"$hash"}]}""") },
            { addFile(it, preview = """{"pages":[{"compressedBlobHash":"$hash"}]}""") },
            { it.execute("INSERT INTO resources(id,hash,type,storage_mode,external_hash,created_at,updated_at) VALUES('r','$hash','file','external','$hash',1,1)") },
        )
        references.forEachIndexed { index, reference ->
            val host = fixture("referenced-$index")
            host.incoming(SharedDatabase.VFS) { db -> addBlob(db); reference(db) }
            assertRejected(host, host.restore(host.archive()))
        }
    }

    @Test fun oldSlotBytesCannotStandInForAnOmittedArchiveAttachment() {
        val host = fixture("old")
        host.original.resolve("vfs_blobs/$relative").also { checkNotNull(it.parentFile).mkdirs(); it.writeBytes(payload) }
        host.incoming(SharedDatabase.VFS) { db -> addBlob(db); addFile(db, blob = "'$hash'") }
        assertRejected(host, host.restore(host.archive()))
        assertArrayEquals(payload, host.original.resolve("vfs_blobs/$relative").readBytes())
    }

    @Test fun absentBlobMetadataAndInconsistentArchivedHashOrSizeAreRejected() {
        val missing = fixture("missing-metadata")
        missing.incoming(SharedDatabase.VFS) { addFile(it, preview = """{"pages":[{"blobHash":"$hash"}]}""") }
        assertRejected(missing, missing.restore(missing.archive(mapOf(relative to payload))))
        val invalidPayloads = listOf(payload.copyOf().also { it[0] = 0 }, payload + byteArrayOf(0))
        invalidPayloads.forEachIndexed { index, bytes ->
            val host = fixture("invalid-$index")
            host.incoming(SharedDatabase.VFS) { db -> addBlob(db); addFile(db, blob = "'$hash'") }
            assertRejected(host, host.restore(host.archive(mapOf(relative to bytes))))
        }
    }

    private fun verifyDesktopExport(input: File) {
        val host = fixture("desktop")
        val directory = temp.newFolder("portable")
        val source = input.copyTo(directory.resolve("portable.zip"))
        val output = directory.resolve("unsealed.zip")
        val password = "public-s217-fixture".toCharArray()
        val budget = 32L * 1024 * 1024
        try {
            DsbkStagingArea(directory.resolve("staging"), budget).use { staging ->
                val portable = PortableBackupFiles(directory, staging, DsbkCrypto.production(), budget, budget, budget)
                portable.importPortableZip(source, output, password, PortableArchiveVerifier { archive, cancel ->
                    check(ArchivePreflight.inspectAuthenticated(archive, budget, cancel).plan.canStage)
                })
            }
        } finally { password.fill('\u0000') }
        assertCommitted(host.restore(output, admission = RestoreAdmission.AUTHENTICATED_PACKAGE))
        host.open(host.active, SharedDatabase.VFS).use { db ->
            val orphan = db.query("SELECT relative_path FROM blobs WHERE mime_type='application/x-dstu-orphan-fixture' AND ref_count=0") { it.text(0) }.single()
            assertFalse(host.active.resolve("vfs_blobs/$orphan").exists())
            assertTrue(db.query("SELECT count(*) FROM notes") { it.long(0) }.single() > 0)
        }
    }

    private fun fixture(name: String) = HostRestoreFixture(temp.newFolder(name)).also { host ->
        host.original.resolve("old-note").writeText("old slot")
        SharedDatabase.entries.forEach { host.incoming(it) {} }
    }

    private fun addBlob(db: SqlDatabase) = db.execute(
        "INSERT INTO blobs(hash,relative_path,size,mime_type,ref_count,created_at) VALUES('$hash','$relative',${payload.size},'text/plain',0,1)",
    )

    private fun addFile(db: SqlDatabase, blob: String = "NULL", compressed: String = "NULL", preview: String = "{}", deleted: String = "NULL") {
        val sha = if (blob == "NULL") "f".repeat(64) else hash
        db.execute("""INSERT INTO files(id,blob_hash,compressed_blob_hash,sha256,file_name,size,preview_json,created_at,updated_at,deleted_at)
            VALUES('file',$blob,$compressed,'$sha','import.bin',${payload.size},'$preview','t','t',$deleted)""".trimIndent())
    }

    private fun assertCommitted(result: RestoreExecutionResult) {
        assertEquals(RestoreExecutionResult.Committed("host-restore", CommitDisposition.NEWLY_COMMITTED), result)
    }

    private fun assertRejected(host: HostRestoreFixture, result: RestoreExecutionResult) {
        assertEquals(RestoreExecutionResult.Failed("host-restore", RestorePhase.VERIFY_RESOURCES, RestoreError.RESOURCE_INVALID), result)
        assertEquals(host.original, host.active)
        assertEquals("old slot", host.active.resolve("old-note").readText())
    }
}

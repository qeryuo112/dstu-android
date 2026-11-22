package com.deepstudent.services.resources.data

import com.deepstudent.core.common.error.DstuException
import com.deepstudent.core.common.id.Digests
import com.deepstudent.core.common.time.WallClock
import com.deepstudent.platform.database.DatabaseFactory
import com.deepstudent.platform.database.MigrationSource
import com.deepstudent.platform.database.SharedDatabase
import com.deepstudent.services.resources.api.AttachmentVerification
import com.deepstudent.services.resources.api.CreateAttachment
import com.deepstudent.services.resources.backend.AttachmentService
import java.io.File
import java.nio.file.Files
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SqlAttachmentRepositoryTest {
    private val root = Files.createTempDirectory("attachments-test").toFile()
    private val file = root.resolve("vfs.db")
    private val blobs = root.resolve("vfs_blobs")
    private val repositoryRoot = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .first { File(it, "config/schema/final-lock.json").isFile }
    private val source = MigrationSource { repositoryRoot.resolve(it).readBytes() }
    private val clock = WallClock { Instant.parse("2026-11-16T00:00:00Z") }

    @After fun cleanup() { root.deleteRecursively() }

    @Test fun inlineAndExternalReadDeleteRestoreAndReopen() = runBlocking {
        val small = "hello 世界".toByteArray()
        val large = ByteArray(1024 * 1024) { (it % 251).toByte() }
        var smallId = ""
        var largeId = ""
        DatabaseFactory.openShared(file, SharedDatabase.VFS, source).use { db ->
            val service = AttachmentService(SqlAttachmentRepository(db, blobs, clock))
            val one = service.create(CreateAttachment("hello.txt", "text/plain", small))
            val two = service.create(CreateAttachment("image.bin", "application/octet-stream", large))
            smallId = one.id
            largeId = two.id
            assertNull(one.blobHash)
            assertEquals(Digests.sha256Hex(large), two.blobHash)
            assertEquals(one.id, service.resolve(one.resourceId)?.id)
            assertEquals(two.id, service.create(CreateAttachment("duplicate.bin", "application/octet-stream", large)).id)
            assertEquals(AttachmentVerification.VALID, service.verify(two.id))
            assertEquals("renamed.txt", service.rename(one.id, "renamed.txt").name)
            service.delete(one.id)
            assertNull(service.get(one.id))
            assertEquals(AttachmentVerification.DELETED, service.verify(one.id))
            assertNotNull(service.get(one.id, true))
            service.restore(one.id)
            assertArrayEquals(small, service.get(one.id)?.bytes)
            assertTrue(db.query("PRAGMA foreign_key_check") { it.long(0) }.isEmpty())
            assertTrue(db.query("SELECT 1 FROM __change_log WHERE table_name='files' AND record_id=?", listOf(text(two.id))) { it.long(0) }.isNotEmpty())
        }
        DatabaseFactory.openShared(file, SharedDatabase.VFS, source).use { db ->
            assertEquals(0, db.migrationReport?.appliedCount)
            val service = AttachmentService(SqlAttachmentRepository(db, blobs, clock))
            assertArrayEquals(small, service.get(smallId)?.bytes)
            assertArrayEquals(large, service.get(largeId)?.bytes)
            assertEquals(AttachmentVerification.VALID, service.verify(largeId))
        }
    }

    @Test fun tamperedBytesAndUntrustedBlobPathsNeverVerify() = runBlocking {
        DatabaseFactory.openShared(file, SharedDatabase.VFS, source).use { db ->
            val service = AttachmentService(SqlAttachmentRepository(db, blobs, clock))
            val bytes = ByteArray(1024 * 1024) { 37 }
            val attachment = service.create(CreateAttachment("a.bin", "application/octet-stream", bytes))
            val relative = db.query("SELECT relative_path FROM blobs WHERE hash=?", listOf(text(attachment.sha256))) { it.text(0) }.single()
            blobs.resolve(relative).writeBytes(ByteArray(bytes.size) { 38 })
            assertEquals(AttachmentVerification.HASH_MISMATCH, service.verify(attachment.id))
            assertThrows(DstuException::class.java) { runBlocking { service.get(attachment.id) } }
            db.execute("UPDATE blobs SET relative_path='../secret' WHERE hash=?", listOf(text(attachment.sha256)))
            assertThrows(DstuException::class.java) { runBlocking { service.get(attachment.id) } }
            Unit
        }
    }

    @Test fun noteResourceOwnerParticipatesInCallerRollback() {
        DatabaseFactory.openShared(file, SharedDatabase.VFS, source).use { db ->
            assertThrows(IllegalStateException::class.java) {
                db.transaction {
                    SqlInlineResourceWriter().put(
                        this,
                        com.deepstudent.services.resources.api.InlineNoteResource(
                            com.deepstudent.core.common.id.ResourceId.parse("res_test"), "note_test", "hello", 1L,
                        ),
                    )
                    throw IllegalStateException("fixture")
                }
            }
            assertEquals(listOf(0L), db.query("SELECT count(*) FROM resources") { it.long(0) })
        }
    }

    @Test fun symlinkedHashDirectoryCannotEscapePrivateRoot() = runBlocking {
        DatabaseFactory.openShared(file, SharedDatabase.VFS, source).use { db ->
            val bytes = ByteArray(1024 * 1024) { 81 }
            val hash = Digests.sha256Hex(bytes)
            val outside = root.resolve("outside").apply { mkdirs() }
            blobs.mkdirs()
            Files.createSymbolicLink(blobs.resolve(hash.take(2)).toPath(), outside.toPath())
            val service = AttachmentService(SqlAttachmentRepository(db, blobs, clock))
            assertThrows(DstuException::class.java) {
                runBlocking { service.create(CreateAttachment("a.bin", "application/octet-stream", bytes)) }
            }
            assertTrue(outside.listFiles().orEmpty().isEmpty())
            assertEquals(listOf(0L), db.query("SELECT count(*) FROM files") { it.long(0) })
        }
    }
}

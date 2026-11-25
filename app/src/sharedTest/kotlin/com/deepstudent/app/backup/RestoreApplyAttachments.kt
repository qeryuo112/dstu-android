package com.deepstudent.app.backup

import com.deepstudent.core.common.id.Digests
import com.deepstudent.core.common.time.SystemWallClock
import com.deepstudent.features.notes.backend.application.NotesService
import com.deepstudent.platform.database.SqlDatabase
import com.deepstudent.platform.database.SqlValue
import com.deepstudent.services.resources.api.AttachmentVerification
import com.deepstudent.services.resources.api.StreamAttachmentRequest
import com.deepstudent.services.resources.backend.AttachmentService
import com.deepstudent.services.resources.data.SqlAttachmentRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import java.io.ByteArrayOutputStream
import java.io.File

/** Checks actual service and SQLite results after the restored installation rejoins the desktop repository. */
internal class RestoreApplyAttachments(private val checkpoints: File, private val param: (String) -> String) {
    suspend fun verifyIncoming(db: SqlDatabase, root: File, notes: NotesService, noteId: String) {
        val attachments = attachments(db, root)
        assertEquals("Desktop second batch edited", notes.get(noteId)?.content)
        assertNull(notes.get(param("REMOVED_NOTE")))
        assertNotNull(notes.get(param("REMOVED_NOTE"), includeDeleted = true)?.deletedAt)
        assertNull(attachments.get(param("ORIGINAL_FILE")))
        assertEquals(AttachmentVerification.DELETED, attachments.verify(param("ORIGINAL_FILE")))
        val newFileId = param("INCOMING_FILE")
        val output = ByteArrayOutputStream()
        val metadata = checkNotNull(attachments.copyTo(newFileId, output))
        val bytes = output.toByteArray()
        assertEquals("desktop-restored-live.txt", metadata.name)
        assertEquals(param("INCOMING_HASH"), metadata.blobHash)
        assertEquals(param("INCOMING_HASH"), Digests.sha256Hex(bytes))
        assertArrayEquals(ByteArray(1024 * 1024 + 47) { 0x63 }, bytes)
        assertTrue(notes.attachmentLinks(noteId).any { it.resourceId == metadata.resourceId && it.targetAvailable })
        assertEquals(1L, db.query("SELECT is_favorite FROM files WHERE id=?", listOf(SqlValue.Text(newFileId))) { it.long(0) }.single())
        assertTrue(db.query("PRAGMA foreign_key_check") { it.text(0) }.isEmpty())
    }

    suspend fun createOutgoing(db: SqlDatabase, root: File, notes: NotesService, noteId: String): OutgoingRestoreAttachment {
        val bytes = ByteArray(1024 * 1024 + 31) { 0x62 }
        val service = attachments(db, root)
        val request = StreamAttachmentRequest("restored-android.txt", "text/plain", bytes.size.toLong())
        val attachment = bytes.inputStream().use { service.createStream(request, it) }
        assertEquals(Digests.sha256Hex(bytes), attachment.blobHash)
        assertTrue(notes.linkAttachment(noteId, attachment.resourceId).targetAvailable)
        return OutgoingRestoreAttachment(service, attachment.id)
    }

    suspend fun awaitDesktopReadback() {
        val ready = checkpoints.resolve("s230-android-attachment-ready")
        val checked = checkpoints.resolve("s230-desktop-readback")
        check(!checked.exists() && ready.createNewFile()) { "restore test checkpoint already exists" }
        try {
            withTimeout(45_000) {
                while (!checked.isFile) delay(200)
            }
        } finally {
            ready.delete()
            checked.delete()
        }
    }

    private fun attachments(db: SqlDatabase, root: File) =
        AttachmentService(SqlAttachmentRepository(db, root.resolve("vfs_blobs"), SystemWallClock))
}

internal class OutgoingRestoreAttachment(private val service: AttachmentService, private val id: String) {
    suspend fun rename() {
        assertEquals("restored-android-renamed.txt", service.rename(id, "restored-android-renamed.txt").name)
    }

    suspend fun delete() {
        assertNotNull(service.delete(id).deletedAt)
        assertNull(service.get(id))
        assertEquals(AttachmentVerification.DELETED, service.verify(id))
    }
}

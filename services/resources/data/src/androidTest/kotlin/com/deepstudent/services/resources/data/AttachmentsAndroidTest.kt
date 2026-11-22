package com.deepstudent.services.resources.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.deepstudent.core.common.time.SystemWallClock
import com.deepstudent.platform.database.BundledMigrationSource
import com.deepstudent.platform.database.DatabaseFactory
import com.deepstudent.platform.database.SharedDatabase
import com.deepstudent.services.resources.api.AttachmentVerification
import com.deepstudent.services.resources.api.CreateAttachment
import com.deepstudent.services.resources.backend.AttachmentService
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AttachmentsAndroidTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = File(context.cacheDir, "s1-06-assets-${System.nanoTime()}").apply { mkdirs() }
    private val file = root.resolve("vfs.db")
    private val blobs = root.resolve("vfs_blobs")
    private val source = BundledMigrationSource(context)

    @After fun cleanup() { root.deleteRecursively() }

    @Test fun inlineAndExternalFilesSurviveReopenAndRestore() = runBlocking {
        val small = "附件 🧪".toByteArray()
        val large = ByteArray(1024 * 1024) { (it % 251).toByte() }
        var first = ""
        var second = ""
        DatabaseFactory.openShared(file, SharedDatabase.VFS, source).use { db ->
            val attachments = AttachmentService(SqlAttachmentRepository(db, blobs, SystemWallClock))
            val inline = attachments.create(CreateAttachment("a.txt", "text/plain", small))
            val external = attachments.create(CreateAttachment("b.bin", "application/octet-stream", large))
            first = inline.id
            second = external.id
            assertEquals(inline.id, attachments.resolve(inline.resourceId)?.id)
            assertEquals(AttachmentVerification.VALID, attachments.verify(external.id))
            attachments.delete(first)
            assertNull(attachments.get(first))
            attachments.restore(first)
            assertArrayEquals(small, attachments.get(first)?.bytes)
        }
        DatabaseFactory.openShared(file, SharedDatabase.VFS, source).use { db ->
            assertEquals(0, db.migrationReport?.appliedCount)
            val attachments = AttachmentService(SqlAttachmentRepository(db, blobs, SystemWallClock))
            assertArrayEquals(small, attachments.get(first)?.bytes)
            assertArrayEquals(large, attachments.get(second)?.bytes)
            assertEquals(AttachmentVerification.VALID, attachments.verify(second))
        }
    }
}

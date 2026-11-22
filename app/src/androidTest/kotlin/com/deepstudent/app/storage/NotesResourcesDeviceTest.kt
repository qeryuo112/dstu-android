package com.deepstudent.app.storage

import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.deepstudent.core.common.id.Digests
import com.deepstudent.core.common.error.DstuException
import com.deepstudent.features.notes.api.CreateNote
import com.deepstudent.features.notes.api.EditNote
import com.deepstudent.services.resources.api.AttachmentVerification
import com.deepstudent.services.resources.api.CreateAttachment
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NotesResourcesDeviceTest {
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = File(base.cacheDir, "s1-06-device-${System.nanoTime()}").apply { mkdirs() }
    private val context = object : ContextWrapper(base) {
        override fun getFilesDir(): File = root
    }

    @After fun cleanup() { root.deleteRecursively() }

    @Test fun composedNoteAndAttachmentsSurviveReopen() = runBlocking {
        withContext(Dispatchers.IO) {
            val bytes = ByteArray(1024 * 1024) { (it % 239).toByte() }
            val digest = Digests.sha256Hex(bytes)
            var noteId = ""
            var attachmentId = ""
            MainStorageResources.open(context).use { storage ->
                val attachment = storage.attachments.create(CreateAttachment("sample.bin", "application/octet-stream", bytes))
                attachmentId = attachment.id
                assertEquals(digest, attachment.sha256)
                assertEquals(attachment.id, storage.attachments.resolve(attachment.resourceId)?.id)
                val created = storage.notes.create(CreateNote("测试笔记", "正文 🧪", listOf("中文")))
                noteId = created.id
                val link = storage.notes.linkAttachment(noteId, attachment.resourceId)
                assertEquals(attachment.resourceId, link.resourceId)
                val updated = storage.notes.edit(EditNote(noteId, created.updatedAt, "测试笔记二", "正文二 🧪", listOf("附件")))
                assertNotEquals(created.resourceId, updated.resourceId)
                storage.notes.delete(noteId)
                assertNull(storage.notes.get(noteId))
                assertEquals("正文二 🧪", storage.notes.restore(noteId).content)
                storage.attachments.delete(attachmentId)
                assertNull(storage.attachments.get(attachmentId))
                storage.attachments.restore(attachmentId)
            }
            MainStorageResources.open(context).use { storage ->
                assertEquals("正文二 🧪", storage.notes.get(noteId)?.content)
                assertEquals(1, storage.notes.attachmentLinks(noteId).size)
                assertEquals(digest, storage.attachments.get(attachmentId)?.metadata?.sha256)
                assertArrayEquals(bytes, storage.attachments.get(attachmentId)?.bytes)
                assertEquals(AttachmentVerification.VALID, storage.attachments.verify(attachmentId))
            }
        }
    }

    @Test fun externalPublicationReportsOnlyFixedFailureDetails(): Unit = runBlocking {
        withContext(Dispatchers.IO) {
            try {
                MainStorageResources.open(context).use { storage ->
                    storage.attachments.create(CreateAttachment(
                        "diagnostic.bin", "application/octet-stream", ByteArray(1024 * 1024) { 47 },
                    ))
                }
                Unit
            } catch (failure: DstuException) {
                throw AssertionError("storageDiagnostic=${failure.error.details}")
            }
        }
    }
}

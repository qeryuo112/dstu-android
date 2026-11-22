package com.deepstudent.app.storage

import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.deepstudent.core.common.error.DstuException
import com.deepstudent.core.common.id.Digests
import com.deepstudent.features.notes.api.CreateNote
import com.deepstudent.services.resources.api.CreateAttachment
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** Independent check of the production App composition with an isolated on-device data space. */
@RunWith(AndroidJUnit4::class)
class SharedResourceStorageDeviceTest {
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = File(base.cacheDir, "s2-06-composition-${System.nanoTime()}").apply { check(mkdirs()) }
    private val context = object : ContextWrapper(base) {
        override fun getFilesDir(): File = root
    }

    @After fun cleanup() { check(root.deleteRecursively()) }

    @Test fun deduplicatedExternalAttachmentSurvivesASharedNoteDeleteAndRestore(): Unit = runBlocking {
        withContext(Dispatchers.IO) {
            val payload = ByteArray(1536 * 1024) { (it % 251).toByte() }
            val digest = Digests.sha256Hex(payload)
            val (attachment, first, second) = MainStorageResources.open(context).use { storage ->
                val original = storage.attachments.create(CreateAttachment("first.bin", "application/octet-stream", payload))
                val repeated = storage.attachments.create(CreateAttachment("second.bin", "application/octet-stream", payload))
                assertEquals(original.id, repeated.id)
                assertEquals(digest, original.sha256)
                val first = storage.notes.create(CreateNote("First fixture note", "one"))
                val second = storage.notes.create(CreateNote("Second fixture note", "two"))
                storage.notes.linkAttachment(first.id, original.resourceId)
                storage.notes.linkAttachment(second.id, original.resourceId)
                storage.notes.delete(first.id)
                assertNull(storage.notes.get(first.id))
                assertEquals(original.resourceId, storage.notes.attachmentLinks(second.id).single().resourceId)
                assertArrayEquals(payload, storage.attachments.get(original.id)?.bytes)
                Triple(original, first.id, second.id)
            }
            MainStorageResources.open(context).use { storage ->
                assertEquals("one", storage.notes.restore(first).content)
                for (id in listOf(first, second)) {
                    val link = storage.notes.attachmentLinks(id).single()
                    assertEquals(attachment.id, storage.attachments.resolve(link.resourceId)?.id)
                }
                val db = storage.databaseForSync()
                val relative = db.query("SELECT relative_path FROM blobs") { it.text(0) }.single()
                val files = root.resolve("active/vfs_blobs").walkTopDown().filter { it.isFile }.toList()
                assertEquals(listOf(root.resolve("active/vfs_blobs/$relative")), files.filter { !it.name.startsWith('.') })
                assertEquals(digest, Digests.sha256Hex(root.resolve("active/vfs_blobs/$relative").readBytes()))
                assertArrayEquals(payload, storage.attachments.get(attachment.id)?.bytes)
            }
        }
    }

    @Test fun corruptHashTargetIsNotOverwrittenOrRegistered(): Unit = runBlocking {
        withContext(Dispatchers.IO) {
            val bytes = ByteArray(1024 * 1024 + 3) { 31 }
            val digest = Digests.sha256Hex(bytes)
            val wrong = byteArrayOf(1, 2, 3)
            MainStorageResources.open(context).use { storage ->
                val target = root.resolve("active/vfs_blobs/${digest.take(2)}/$digest.bin")
                check(target.parentFile!!.mkdirs())
                target.writeBytes(wrong)
                val result = runCatching {
                    storage.attachments.create(CreateAttachment("s206-diagnostic-redaction-canary.bin", "application/octet-stream", bytes))
                }
                val failure = result.exceptionOrNull() as? DstuException
                assertEquals("ATTACHMENT_CORRUPT", failure?.error?.code)
                assertArrayEquals(wrong, target.readBytes())
                val db = storage.databaseForSync()
                assertEquals(0L, db.query("SELECT count(*) FROM blobs") { it.long(0) }.single())
                assertEquals(0L, db.query("SELECT count(*) FROM files") { it.long(0) }.single())
            }
        }
    }
}

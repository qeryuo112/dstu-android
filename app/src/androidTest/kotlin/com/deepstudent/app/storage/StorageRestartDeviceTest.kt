package com.deepstudent.app.storage

import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.deepstudent.core.common.id.Digests
import com.deepstudent.features.notes.api.CreateNote
import com.deepstudent.features.notes.api.EditNote
import com.deepstudent.services.resources.api.CreateAttachment
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Run each method separately, with an adb force-stop between write and read. */
@RunWith(AndroidJUnit4::class)
class StorageRestartDeviceTest {
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = File(base.cacheDir, "s1-06-process-restart")
    private val context = object : ContextWrapper(base) {
        override fun getFilesDir(): File = root
    }
    private val bytes = "s1-06 restart fixture 中文".toByteArray()

    @Test fun writeRestartFixture(): Unit = runBlocking {
        selected("writeRestartFixture")
        withContext(Dispatchers.IO) {
            check(root.mkdirs() || root.isDirectory)
            MainStorageResources.open(context).use { storage ->
                val attachment = storage.attachments.create(CreateAttachment("restart.txt", "text/plain", bytes))
                val note = storage.notes.create(CreateNote("重启测试", "写入 🧪"))
                storage.notes.linkAttachment(note.id, attachment.resourceId)
                storage.notes.edit(EditNote(note.id, note.updatedAt, "重启测试", "编辑 🧪", emptyList()))
                storage.notes.delete(note.id)
                storage.notes.restore(note.id)
                File(root, "fixture-ids").writeText("${note.id}\n${attachment.id}\n${android.os.Process.myPid()}")
            }
        }
    }

    @Test fun readRestartFixture(): Unit = runBlocking {
        selected("readRestartFixture")
        withContext(Dispatchers.IO) {
            val ids = File(root, "fixture-ids").readLines()
            assertTrue("Requires a real process restart", ids[2].toInt() != android.os.Process.myPid())
            try {
                MainStorageResources.open(context).use { storage ->
                    assertEquals("编辑 🧪", storage.notes.get(ids[0])?.content)
                    val attachment = checkNotNull(storage.attachments.get(ids[1]))
                    assertArrayEquals(bytes, attachment.bytes)
                    assertEquals(Digests.sha256Hex(bytes), attachment.metadata.sha256)
                    assertEquals(attachment.metadata.resourceId, storage.notes.attachmentLinks(ids[0]).single().resourceId)
                }
            } finally {
                root.deleteRecursively()
            }
        }
    }

    private fun selected(method: String) {
        assumeTrue("Explicit host restart sequence required", InstrumentationRegistry.getArguments()
            .getString("class") == "${javaClass.name}#$method")
    }
}

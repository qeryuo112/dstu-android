package com.deepstudent.app.storage

import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.deepstudent.features.notes.api.CreateNote
import com.deepstudent.platform.storage.ActivationResult
import com.deepstudent.platform.storage.DataSpaceError
import com.deepstudent.platform.storage.DataSpaceException
import com.deepstudent.platform.storage.FileDataSpace
import com.deepstudent.services.resources.api.CreateAttachment
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** A real migrated VFS and blob set in each slot. Never reads or switches the user's filesDir. */
@RunWith(AndroidJUnit4::class)
class DataSpaceCompositionDeviceTest {
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val fixture = Files.createTempDirectory(base.cacheDir.toPath(), "s209-composition-").toFile()
    private val target = fixture.resolve("target").apply { check(mkdir()) }
    private val incoming = fixture.resolve("incoming").apply { check(mkdir()) }
    private val dataSpace = FileDataSpace(target, audit = AndroidDataSpaceAudit)

    @After fun clean() { check(fixture.deleteRecursively()) }

    @Test fun realDatabaseResourcesAndLogicalAssetKeysFollowTheSelectedSlot(): Unit = runBlocking {
        val oldSlot = target.resolve("active")
        val oldPayload = byteArrayOf(1, 2, 3)
        val old = MainStorageResources.open(context(target)).use {
            assertEquals(oldSlot.canonicalFile, it.activeRoot)
            val refusal = assertThrows(DataSpaceException::class.java) {
                dataSpace.withMaintenance { error("maintenance must not open while the storage lease is live") }
            }
            assertEquals(DataSpaceError.BUSY, refusal.error)
            populate(it, "old", oldPayload)
        }
        val newPayload = ByteArray(1536 * 1024) { (it % 87).toByte() }
        val added = MainStorageResources.open(context(incoming)).use { populate(it, "new", newPayload) }
        dataSpace.withMaintenance {
            val staged = prepare("s209-composition")
            check(incoming.resolve("active").copyRecursively(staged.root, overwrite = true))
            assertEquals(ActivationResult.ACTIVATED, activate(staged) { data ->
                check(data.resolve("databases/vfs.db").length() > 0 && data.resolve("vfs_blobs").isDirectory)
            })
        }
        MainStorageResources.open(context(target)).use { after ->
            assertNotEquals(oldSlot.canonicalFile, after.activeRoot)
            assertEquals("new", after.notes.get(added.note.id)?.content)
            assertNull(after.notes.get(old.note.id))
            assertArrayEquals(newPayload, after.attachments.get(added.attachment.id)?.bytes)
            val relative = after.databaseForSync().query("SELECT relative_path FROM blobs") { it.text(0) }.single()
            assertArrayEquals(newPayload, after.activeRoot.resolve("vfs_blobs/$relative").readBytes())
            assertEquals(setOf("active/anki_media/new.wav"), after.assetLedger.scan().manifest.entries.keys)
        }
        assertTrue(oldSlot.resolve("databases/vfs.db").isFile)
        MainStorageResources.open(context(incoming)).use { assertEquals("new", it.notes.get(added.note.id)?.content) }
        assertArrayEquals(oldPayload, oldSlot.resolve("anki_media/old.wav").readBytes())
    }

    private suspend fun populate(store: MainStorageResources, name: String, payload: ByteArray): FixtureData {
        store.activeRoot.resolve("anki_media").apply { check(mkdirs()); resolve("$name.wav").writeBytes(payload) }
        val note = store.notes.create(CreateNote("$name fixture", name))
        val attachment = store.attachments.create(CreateAttachment("$name.bin", "application/octet-stream", payload))
        store.notes.linkAttachment(note.id, attachment.resourceId)
        return FixtureData(note, attachment)
    }

    private fun context(root: File) = object : ContextWrapper(base) {
        override fun getFilesDir() = root
        override fun getCacheDir() = fixture.resolve("ops-cache").apply { mkdirs() }
    }
}

private data class FixtureData(
    val note: com.deepstudent.features.notes.api.NoteDocument,
    val attachment: com.deepstudent.services.resources.api.AttachmentMetadata,
)

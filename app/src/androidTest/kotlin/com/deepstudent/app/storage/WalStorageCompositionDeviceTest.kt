package com.deepstudent.app.storage

import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.deepstudent.features.notes.api.CreateNote
import com.deepstudent.features.notes.api.EditNote
import com.deepstudent.platform.database.SqlReadOperations
import com.deepstudent.platform.database.SqlValue
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WalStorageCompositionDeviceTest {
    @Test fun noteCommitDoesNotChangeAnOpenVfsSnapshot() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = Files.createTempDirectory(base.cacheDir.toPath(), "s2-vfs-").toFile()
        val context = object : ContextWrapper(base) {
            override fun getFilesDir() = root
        }
        val editor = Executors.newSingleThreadExecutor()
        try {
            MainStorageResources.open(context).use { storage ->
                val note = runBlocking { storage.notes.create(CreateNote("Before", "original body")) }
                val database = storage.databaseForSync()
                database.readSnapshot {
                    assertEquals("Before", noteTitle(note.id))
                    val edit = editor.submit<String> {
                        runBlocking {
                            storage.notes.edit(EditNote(note.id, note.updatedAt, "After", "edited body", emptyList())).title
                        }
                    }
                    assertEquals("After", edit.get(10, TimeUnit.SECONDS))
                    assertEquals("Before", noteTitle(note.id))
                }
                assertEquals("After", database.noteTitle(note.id))
            }
            MainStorageResources.open(context).use { storage ->
                assertEquals("edited body", runBlocking { storage.notes.list().single().content })
            }
        } finally {
            editor.shutdownNow()
            root.deleteRecursively()
        }
    }

    private fun SqlReadOperations.noteTitle(id: String): String =
        query("SELECT title FROM notes WHERE id = ?", listOf(SqlValue.Text(id))) { it.text(0) }.single()
}

package com.deepstudent.features.notes.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.deepstudent.core.common.id.Digests
import com.deepstudent.core.common.id.ResourceId
import com.deepstudent.core.common.time.SystemWallClock
import com.deepstudent.features.notes.api.CreateNote
import com.deepstudent.features.notes.api.EditNote
import com.deepstudent.features.notes.backend.application.NotesService
import com.deepstudent.platform.database.BundledMigrationSource
import com.deepstudent.platform.database.DatabaseFactory
import com.deepstudent.platform.database.SharedDatabase
import com.deepstudent.platform.database.SqlValue
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NotesAndroidTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = File(context.cacheDir, "s1-06-notes-${System.nanoTime()}").apply { mkdirs() }
    private val file = root.resolve("vfs.db")
    private val source = BundledMigrationSource(context)

    @After fun cleanup() { root.deleteRecursively() }

    @Test fun noteReferencesAndTombstonesSurviveDatabaseReopen() = runBlocking {
        var id = ""
        var relation = ""
        val resource = ResourceId.parse("res_attachment")
        DatabaseFactory.openShared(file, SharedDatabase.VFS, source).use { db ->
            db.execute(
                "INSERT INTO resources(id,hash,type,storage_mode,data,created_at,updated_at) VALUES (?,?,'file','inline','YQ==',1,1)",
                listOf(text(resource.value), text(Digests.sha256HexUtf8("YQ=="))),
            )
            db.execute(
                """INSERT INTO files(id,resource_id,sha256,file_name,size,created_at,updated_at,type,name,mime_type)
                    VALUES('att_a',?,?,'a',1,'now','now','file','a','text/plain')""".trimIndent(),
                listOf(text(resource.value), text(Digests.sha256HexUtf8("a"))),
            )
            val notes = NotesService(SqlNoteRepository(db, writer(), SystemWallClock))
            val created = notes.create(CreateNote("测试 🧪", "[a](resource://${resource.value})"))
            id = created.id
            relation = notes.linkAttachment(id, resource).id
            val edited = notes.edit(EditNote(id, created.updatedAt, "修改", "正文 [a](resource://${resource.value})", emptyList()))
            assertTrue(notes.attachmentLinks(id).single().targetAvailable)
            notes.delete(id)
            notes.restore(id)
            assertEquals(edited.content, notes.get(id)?.content)
            db.execute("UPDATE files SET status='deleted',deleted_at='2026-11-16T00:00:00Z' WHERE id='att_a'")
            assertNotNull(notes.attachmentLinks(id).single().invalidatedAt)
            assertTrue(db.query("PRAGMA foreign_key_check") { it.long(0) }.isEmpty())
        }
        DatabaseFactory.openShared(file, SharedDatabase.VFS, source).use { db ->
            assertEquals(0, db.migrationReport?.appliedCount)
            val notes = NotesService(SqlNoteRepository(db, writer(), SystemWallClock))
            assertEquals(relation, notes.attachmentLinks(id).single().id)
            assertEquals("正文 [a](resource://res_attachment)", notes.get(id)?.content)
        }
    }

    private fun writer() = NoteResourceWriter { tx, input ->
        tx.execute(
            """INSERT INTO resources(id,hash,type,source_id,source_table,storage_mode,data,created_at,updated_at)
                VALUES(?,?,'note',?,'notes','inline',?,?,?)""".trimIndent(),
            listOf(
                text(input.resourceId), text(Digests.sha256HexUtf8("${input.noteId}:${input.content}")), text(input.noteId),
                text(input.content), SqlValue.Integer(input.nowMillis), SqlValue.Integer(input.nowMillis),
            ),
        )
        input.resourceId
    }
}

package com.deepstudent.features.notes.data

import com.deepstudent.core.common.error.DstuException
import com.deepstudent.core.common.id.Digests
import com.deepstudent.core.common.id.ResourceId
import com.deepstudent.core.common.time.WallClock
import com.deepstudent.features.notes.api.CreateNote
import com.deepstudent.features.notes.api.EditNote
import com.deepstudent.features.notes.backend.application.NotesService
import com.deepstudent.platform.database.DatabaseFactory
import com.deepstudent.platform.database.MigrationSource
import com.deepstudent.platform.database.SharedDatabase
import com.deepstudent.platform.database.SqlOperations
import com.deepstudent.platform.database.SqlValue
import java.io.File
import java.nio.file.Files
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SqlNoteRepositoryTest {
    private val root = Files.createTempDirectory("notes-test").toFile()
    private val databaseFile = root.resolve("vfs.db")
    private val repositoryRoot = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .first { File(it, "config/schema/final-lock.json").isFile }
    private val source = MigrationSource { repositoryRoot.resolve(it).readBytes() }
    private val clock = WallClock { Instant.parse("2026-11-16T00:00:00Z") }

    @After fun cleanup() { root.deleteRecursively() }

    @Test fun notesSurviveEditTrashRestoreAndMigratedDatabaseReopen() = runBlocking {
        var id = ""
        var resource = ""
        DatabaseFactory.openShared(databaseFile, SharedDatabase.VFS, source).use { db ->
            val service = NotesService(SqlNoteRepository(db, testWriter(), clock))
            val created = service.create(CreateNote("标题", "正文 🧪", listOf("测试")))
            id = created.id
            resource = created.resourceId.value
            val edited = service.edit(EditNote(id, created.updatedAt, "标题二", "正文2 🧪", listOf("甲")))
            assertNotEquals(resource, edited.resourceId.value)
            assertTrue(edited.updatedAt > created.updatedAt)
            assertTrue(db.query("SELECT 1 FROM note_tags WHERE note_id=? AND tag=?", listOf(text(id), text("甲"))) { it.long(0) }.isNotEmpty())
            val deleted = service.delete(id)
            assertNotNull(deleted.deletedAt)
            assertNull(service.get(id))
            assertEquals("正文2 🧪", service.restore(id).content)
            assertEquals(listOf(0L), db.query("SELECT COUNT(*) FROM folder_items WHERE item_id=? AND deleted_at IS NOT NULL", listOf(text(id))) { it.long(0) })
            assertTrue(db.query("SELECT 1 FROM __change_log WHERE table_name='notes' AND record_id=?", listOf(text(id))) { it.long(0) }.isNotEmpty())
            assertTrue(db.query("PRAGMA foreign_key_check") { it.long(0) }.isEmpty())
        }
        DatabaseFactory.openShared(databaseFile, SharedDatabase.VFS, source).use { db ->
            assertEquals(0, db.migrationReport?.appliedCount)
            val note = NotesService(SqlNoteRepository(db, testWriter(), clock)).get(id)!!
            assertEquals("标题二", note.title)
            assertEquals("正文2 🧪", note.content)
            assertNotEquals(resource, note.resourceId.value)
            assertTrue(db.query("SELECT 1 FROM note_document_revisions WHERE note_id=?", listOf(text(id))) { it.long(0) }.isNotEmpty())
        }
    }

    @Test fun resourceFailureAndCancellationRollbackWholeWrite() = runBlocking {
        DatabaseFactory.openShared(databaseFile, SharedDatabase.VFS, source).use { db ->
            val failure = NoteResourceWriter { tx, input ->
                testWriter().put(tx, input)
                throw IllegalStateException("fixture")
            }
            assertThrows(IllegalStateException::class.java) {
                runBlocking { NotesService(SqlNoteRepository(db, failure, clock)).create(CreateNote("a", "b")) }
            }
            assertEquals(listOf(0L), db.query("SELECT COUNT(*) FROM notes") { it.long(0) })
            assertEquals(listOf(0L), db.query("SELECT COUNT(*) FROM resources") { it.long(0) })
            assertThrows(CancellationException::class.java) {
                runBlocking {
                    val context = currentCoroutineContext()
                    val cancelWriter = NoteResourceWriter { tx, input ->
                        val id = testWriter().put(tx, input)
                        context.cancel()
                        id
                    }
                    NotesService(SqlNoteRepository(db, cancelWriter, clock)).create(CreateNote("a", "b"))
                }
            }
            assertEquals(listOf(0L), db.query("SELECT COUNT(*) FROM notes") { it.long(0) })
            assertEquals(listOf(0L), db.query("SELECT COUNT(*) FROM resources") { it.long(0) })
        }
    }

    @Test fun staleVersionMissingFolderAndUnsupportedFormatAreRejected() = runBlocking {
        DatabaseFactory.openShared(databaseFile, SharedDatabase.VFS, source).use { db ->
            val service = NotesService(SqlNoteRepository(db, testWriter(), clock))
            val note = service.create(CreateNote("a", "one"))
            assertThrows(DstuException::class.java) {
                runBlocking { service.edit(EditNote(note.id, "old", "a", "two", emptyList())) }
            }
            assertThrows(DstuException::class.java) {
                runBlocking { service.create(CreateNote("b", "x", folderId = "fld_missing")) }
            }
            assertThrows(DstuException::class.java) {
                runBlocking { service.edit(EditNote(note.id, note.updatedAt, "a", ":::ds-columns{version=1 layout=equal}", emptyList())) }
            }
            assertEquals("one", service.get(note.id)?.content)
            assertEquals(listOf(1L), db.query("SELECT COUNT(*) FROM notes") { it.long(0) })
            assertFalse(db.query("SELECT 1 FROM resources WHERE data='x'") { it.long(0) }.isNotEmpty())
        }
    }

    @Test fun unknownPropsAndAssetReferencesArePreserved() = runBlocking {
        DatabaseFactory.openShared(databaseFile, SharedDatabase.VFS, source).use { db ->
            val service = NotesService(SqlNoteRepository(db, testWriter(), clock))
            val note = service.create(CreateNote("a", "plain"))
            val props = """{"unknown":{"value":9007199254740993},"nullValue":null}"""
            db.execute("UPDATE notes SET props=? WHERE id=?", listOf(text(props), text(note.id)))
            val edited = service.edit(EditNote(note.id, note.updatedAt, "b", "changed", emptyList()))
            assertEquals(props, edited.props?.literal())
            val saved = db.query("SELECT props_json FROM note_document_revisions WHERE note_id=? ORDER BY seq DESC LIMIT 1", listOf(text(note.id))) { it.text(0) }
            assertEquals(listOf(props), saved)
            val content = "![x](notes_assets/a.png) [web](https://example.test/x) [[other]]"
            val linked = service.edit(EditNote(note.id, edited.updatedAt, "c", content, emptyList()))
            assertEquals(content, service.get(note.id)?.content)
            val refs = db.query("SELECT asset_refs_json FROM note_document_revisions WHERE note_id=? ORDER BY seq DESC LIMIT 1", listOf(text(linked.id))) { it.text(0) }.single()
            assertTrue(refs.contains("notes_assets/a.png"))
            assertTrue(refs.contains("https://example.test/x"))
        }
    }

    @Test fun typedAttachmentRelationsUseDesktopTableAndSurviveRestartAndTrash() = runBlocking {
        val resourceId = ResourceId.parse("res_attachment")
        var noteId = ""
        var linkId = ""
        DatabaseFactory.openShared(databaseFile, SharedDatabase.VFS, source).use { db ->
            insertAttachment(db, resourceId.value)
            val service = NotesService(SqlNoteRepository(db, testWriter(), clock))
            val content = "[attachment](resource://${resourceId.value})"
            val note = service.create(CreateNote("a", content))
            noteId = note.id
            val link = service.linkAttachment(note.id, resourceId)
            linkId = link.id
            assertTrue(link.targetAvailable)
            assertEquals(link.id, service.linkAttachment(note.id, resourceId).id)
            assertEquals(listOf("source:{\"type\":\"whole\"}"), db.query(
                "SELECT relation_type || ':' || locator_json FROM note_learning_relations WHERE id=?", listOf(text(link.id)),
            ) { it.text(0) })
            val edited = service.edit(EditNote(note.id, note.updatedAt, "b", "$content updated", emptyList()))
            assertEquals(link.id, service.attachmentLinks(edited.id).single().id)
            service.delete(note.id)
            service.restore(note.id)
            assertEquals(link.id, service.attachmentLinks(note.id).single().id)
            db.execute("UPDATE files SET deleted_at='2026-11-16T00:00:01.000Z',status='deleted' WHERE id='att_attachment'")
            val invalidated = service.attachmentLinks(note.id).single()
            assertFalse(invalidated.targetAvailable)
            assertNotNull(invalidated.invalidatedAt)
            db.execute("UPDATE files SET deleted_at=NULL,status='active' WHERE id='att_attachment'")
            val restored = service.attachmentLinks(note.id).single()
            assertTrue(restored.targetAvailable)
            assertNotNull(restored.invalidatedAt)
            assertThrows(DstuException::class.java) {
                runBlocking { service.linkAttachment(note.id, ResourceId.parse("res_missing")) }
            }
        }
        DatabaseFactory.openShared(databaseFile, SharedDatabase.VFS, source).use { db ->
            val service = NotesService(SqlNoteRepository(db, testWriter(), clock))
            assertEquals(linkId, service.attachmentLinks(noteId).single().id)
            assertEquals("[attachment](resource://res_attachment) updated", service.get(noteId)?.content)
        }
    }

    private fun insertAttachment(tx: SqlOperations, resourceId: String) {
        tx.execute(
            "INSERT INTO resources (id,hash,type,storage_mode,data,created_at,updated_at) VALUES (?,?,'file','inline','YQ==',1,1)",
            listOf(text(resourceId), text(Digests.sha256HexUtf8("YQ=="))),
        )
        tx.execute(
            """INSERT INTO files (id,resource_id,sha256,file_name,size,created_at,updated_at,type,name,mime_type,content_hash)
                VALUES ('att_attachment',?,?,'a.txt',1,'now','now','file','a.txt','text/plain',?)""".trimIndent(),
            listOf(text(resourceId), text(Digests.sha256HexUtf8("a")), text(Digests.sha256HexUtf8("a"))),
        )
    }

    private fun testWriter(): NoteResourceWriter = NoteResourceWriter { tx, value ->
        insertTestResource(tx, value)
        value.resourceId
    }

    private fun insertTestResource(tx: SqlOperations, input: NoteResourceInput) {
        tx.execute(
            """INSERT INTO resources (id,hash,type,source_id,source_table,storage_mode,data,created_at,updated_at)
                VALUES (?,?,'note',?,'notes','inline',?,?,?)""".trimIndent(),
            listOf(
                text(input.resourceId), text(Digests.sha256HexUtf8("${input.noteId}:${input.content}")), text(input.noteId),
                text(input.content), SqlValue.Integer(input.nowMillis), SqlValue.Integer(input.nowMillis),
            ),
        )
    }
}

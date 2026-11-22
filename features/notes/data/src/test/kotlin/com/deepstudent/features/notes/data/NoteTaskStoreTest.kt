package com.deepstudent.features.notes.data

import com.deepstudent.core.common.id.Digests
import com.deepstudent.core.common.time.SystemWallClock
import com.deepstudent.features.notes.api.CreateNote
import com.deepstudent.platform.database.DatabaseFactory
import com.deepstudent.platform.database.MigrationSource
import com.deepstudent.platform.database.SharedDatabase
import com.deepstudent.platform.database.SqlValue
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteTaskStoreTest {
    private val root = Files.createTempDirectory("note-task-test").toFile()
    private val repository = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
        .first { File(it, "config/schema/final-lock.json").isFile }
    private val source = MigrationSource { repository.resolve(it).readBytes() }
    private val file = File(root, "vfs.db")

    @After fun cleanup() { root.deleteRecursively() }

    @Test fun noteFactAndAckAreDurableAndDuplicateCreateIsIdempotent(): Unit = runBlocking {
        val identity = NoteTaskIdentity("task_note_fact", 1)
        var factId = ""
        DatabaseFactory.openShared(file, SharedDatabase.VFS, source).use { db ->
            val store = SqlNoteTaskStore(db, SqlNoteRepository(db, writer, SystemWallClock))
            val fact = store.create(CreateNote("a", "b"), identity)
            factId = fact.factId
            assertEquals(fact, store.create(CreateNote("a", "b"), identity))
            assertEquals(listOf(1L), db.query("SELECT count(*) FROM notes") { it.long(0) })
            assertEquals(listOf(fact), store.pending("runtime.recovery", 100))
            store.acknowledge("runtime.recovery", fact.factId)
            store.acknowledge("runtime.recovery", fact.factId)
        }
        DatabaseFactory.openShared(file, SharedDatabase.VFS, source).use { db ->
            val store = SqlNoteTaskStore(db, SqlNoteRepository(db, writer, SystemWallClock))
            assertEquals(factId, store.find(identity)?.factId)
            assertTrue(store.pending("runtime.recovery", 100).isEmpty())
        }
    }

    @Test fun rejectedFactInsertRollsBackNoteHistoryResourceAndCapture(): Unit = runBlocking {
        DatabaseFactory.openShared(file, SharedDatabase.VFS, source).use { db ->
            val store = SqlNoteTaskStore(db, SqlNoteRepository(db, writer, SystemWallClock))
            db.execute("CREATE TRIGGER fail_fact BEFORE INSERT ON __android_notes_task_facts BEGIN SELECT RAISE(ABORT,'fixture'); END")
            assertThrows(Exception::class.java) {
                runBlocking { store.create(CreateNote("rollback", "content"), NoteTaskIdentity("task_rollback", 1)) }
            }
            for (table in listOf("notes", "resources", "note_document_revisions", "__android_notes_task_facts", "__change_log")) {
                assertEquals(listOf(0L), db.query("SELECT count(*) FROM $table") { it.long(0) })
            }
        }
    }

    private val writer = NoteResourceWriter { tx, input ->
        tx.execute("""INSERT INTO resources(id,hash,type,source_id,source_table,storage_mode,data,created_at,updated_at)
            VALUES(?,?,'note',?,'notes','inline',?,?,?)""".trimIndent(), listOf(
            text(input.resourceId), text(Digests.sha256HexUtf8("${input.noteId}:${input.content}")), text(input.noteId),
            text(input.content), SqlValue.Integer(input.nowMillis), SqlValue.Integer(input.nowMillis),
        ))
        input.resourceId
    }
}

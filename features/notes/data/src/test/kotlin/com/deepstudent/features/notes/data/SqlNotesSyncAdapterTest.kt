package com.deepstudent.features.notes.data

import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.core.common.id.Digests
import com.deepstudent.core.common.id.ResourceId
import com.deepstudent.core.common.time.WallClock
import com.deepstudent.features.notes.api.CreateNote
import com.deepstudent.features.notes.api.EditNote
import com.deepstudent.features.notes.backend.application.NotesService
import java.time.Instant
import kotlinx.coroutines.runBlocking
import com.deepstudent.platform.database.SqlDatabase
import com.deepstudent.platform.database.DatabaseFactory
import com.deepstudent.platform.database.MigrationSource
import com.deepstudent.platform.database.SharedDatabase
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SqlNotesSyncAdapterTest {
    private val root = Files.createTempDirectory("note-sync-adapter").toFile()
    private val repositoryRoot = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .first { File(it, "config/schema/final-lock.json").isFile }
    private val source = MigrationSource { repositoryRoot.resolve(it).readBytes() }
    @After fun cleanup() { root.deleteRecursively() }

    private fun notesAdapter(
        database: SqlDatabase,
        instanceId: String,
        verifyBlob: (String, String, Long) -> Boolean = { _, _, _ -> false },
        checkCancelled: () -> Unit = ::syncCheckCancelled,
    ): SqlNotesSyncAdapter {
        // Test-only source for notes adapter behavior; production capture is supplied by App.
        val source = NotesPendingSource { enrich -> database.readSnapshot {
            val rows = query("SELECT id,table_name,record_id,operation,changed_at FROM __change_log WHERE COALESCE(sync_version,0)=0 ORDER BY id") {
                NoteSyncChange(it.text(1), it.text(2), it.text(3).uppercase(), it.text(4), null, it.long(0))
            }
            val wire = rows.map { row ->
                checkCancelled()
                val data = if (row.operation == "DELETE") null else syncRead(row.table, row.id)
                row.copy(data = data).bytes()
            }
            enrich(this, wire)
        } }
        return SqlNotesSyncAdapter(database, instanceId, verifyBlob, checkCancelled = checkCancelled, pendingSource = source)
    }

    private fun resource(id: String, operation: String = "Insert", extra: String = ""): ByteArray =
        """{"table_name":"resources","record_id":"$id","operation":"$operation","changed_at":"2026-11-15T00:00:00Z","database_name":"vfs","data":{"id":"$id","hash":"hash_$id","type":"note","storage_mode":"inline","data":"body","created_at":9007199254740993,"updated_at":9007199254740993$extra}}""".toByteArray()

    @Test fun realNoteCloneEditDeleteRestore() = runBlocking {
        val clock = WallClock { Instant.parse("2026-11-16T00:00:00Z") }
        val writer = NoteResourceWriter { tx, input ->
            tx.execute("INSERT INTO resources(id,hash,type,source_id,source_table,storage_mode,data,created_at,updated_at) VALUES(?,?,'note',?,'notes','inline',?,?,?)",
                listOf(text(input.resourceId), text(Digests.sha256HexUtf8("${input.noteId}:${input.content}")), text(input.noteId), text(input.content),
                    com.deepstudent.platform.database.SqlValue.Integer(input.nowMillis), com.deepstudent.platform.database.SqlValue.Integer(input.nowMillis)))
            input.resourceId
        }
        DatabaseFactory.openShared(root.resolve("source.db"), SharedDatabase.VFS, source).use { a ->
            DatabaseFactory.openShared(root.resolve("target.db"), SharedDatabase.VFS, source).use { b ->
                val serviceA = NotesService(SqlNoteRepository(a, writer, clock))
                val serviceB = NotesService(SqlNoteRepository(b, writer, clock))
                val sender = notesAdapter(a, "fixture-instance")
                val receiver = notesAdapter(b, "fixture-instance")
                val note = serviceA.create(CreateNote("标题", "正文", listOf("甲")))
                a.execute("INSERT INTO resources(id,hash,type,storage_mode,data,created_at,updated_at) VALUES('res_attachment','attachment-hash','file','inline','YQ==',1,1)")
                a.execute("INSERT INTO files(id,resource_id,sha256,file_name,size,created_at,updated_at,type,name,mime_type,content_hash) VALUES('att_attachment','res_attachment','file-hash','a.txt',1,'2020-01-01T00:00:00Z','2020-01-01T00:00:00Z','file','a.txt','text/plain','file-hash')")
                serviceA.linkAttachment(note.id, ResourceId.parse("res_attachment"))
                val changes = sender.pending()
                receiver.apply("source", 1, changes)
                assertEquals("正文", serviceB.get(note.id)?.content)
                assertEquals(true, serviceB.attachmentLinks(note.id).single().targetAvailable)
                assertEquals(0, receiver.pending().size)
                receiver.apply("source", 1, changes)
                assertEquals(0, receiver.pending().size)
                val edited = serviceB.edit(EditNote(note.id, serviceB.get(note.id)!!.updatedAt, "新标题", "新内容", emptyList()))
                serviceB.delete(edited.id)
                serviceB.restore(edited.id)
                assertEquals("新内容", serviceB.get(note.id)?.content)
                val reverse = receiver.pending()
                assertEquals(true, reverse.isNotEmpty())
                sender.apply("target", 1, reverse)
                assertEquals("新内容", serviceA.get(note.id)?.content)
            }
        }
    }

    @Test fun revisionJsonSemanticEqualityPinAndImmutableConflict() = runBlocking {
        DatabaseFactory.openShared(root.resolve("revision.db"), SharedDatabase.VFS, source).use { db ->
            val clock = WallClock { Instant.parse("2026-11-16T00:00:00Z") }
            val writer = NoteResourceWriter { tx, input ->
                tx.execute("INSERT INTO resources(id,hash,type,source_id,source_table,storage_mode,data,created_at,updated_at) VALUES(?,?,'note',?,'notes','inline',?,?,?)",
                    listOf(text(input.resourceId), text(Digests.sha256HexUtf8("${input.noteId}:${input.content}")), text(input.noteId), text(input.content),
                        com.deepstudent.platform.database.SqlValue.Integer(input.nowMillis), com.deepstudent.platform.database.SqlValue.Integer(input.nowMillis)))
                input.resourceId
            }
            val note = NotesService(SqlNoteRepository(db, writer, clock)).create(CreateNote("a", "body"))
            val id = db.query("SELECT version_id FROM note_document_revisions WHERE note_id=?", listOf(text(note.id))) { it.text(0) }.first()
            db.execute("UPDATE note_document_revisions SET props_json=? WHERE version_id=?", listOf(text("""{"a":9007199254740993,"b":null}"""), text(id)))
            val adapter = notesAdapter(db, "fixture-instance")
            val original = db.syncRead("note_document_revisions", id)!!.asMap()
            val changed = original + ("props_json" to JsonValue.text("""{ "b": null, "a": 9007199254740993 }""")) +
                ("pinned" to JsonValue.integer(1)) + ("future" to JsonValue.integer(9))
            fun bytes(row: Map<String, JsonValue>) = NoteSyncChange("note_document_revisions", id, "INSERT", "2026-11-16T00:00:00Z", JsonValue.Object.from(row)).bytes()
            adapter.apply("peer", 1, listOf(bytes(changed)))
            assertEquals(1, db.query("SELECT pinned FROM note_document_revisions WHERE version_id=?", listOf(text(id))) { it.long(0) }.single())
            val conflict = changed + ("props_json" to JsonValue.text("""{"a":9007199254740992,"b":null}"""))
            assertThrows(IllegalStateException::class.java) { adapter.apply("peer", 2, listOf(bytes(conflict))) }
            assertEquals(1, adapter.cursor("peer"))
        }
    }

    @Test fun folderBusinessKeyPreservesUnknownUnderLocalId() {
        DatabaseFactory.openShared(root.resolve("folder-alias.db"), SharedDatabase.VFS, source).use { db ->
            val adapter = notesAdapter(db, "fixture-instance")
            val folder = """{"table_name":"folders","record_id":"f","operation":"Insert","changed_at":"2023-11-15T00:00:00Z","data":{"id":"f","title":"f","created_at":1,"updated_at":1}}""".toByteArray()
            fun item(id: String, extra: String) = """{"table_name":"folder_items","record_id":"$id","operation":"Insert","changed_at":"2023-11-15T00:00:00Z","data":{"id":"$id","folder_id":"f","item_type":"note","item_id":"n","created_at":1,"updated_at":1$extra}}""".toByteArray()
            adapter.apply("peer", 1, listOf(folder, item("local", "")))
            adapter.apply("peer", 2, listOf(item("remote", ",\"future\":9007199254740993")))
            assertEquals(listOf("local"), db.query("SELECT id FROM folder_items") { it.text(0) })
            assertEquals(JsonValue.integer(9007199254740993), db.syncRead("folder_items", "local")!!["future"])
        }
    }

    @Test fun foldersApplyParentBeforeChild() {
        DatabaseFactory.openShared(root.resolve("folders.db"), SharedDatabase.VFS, source).use { db ->
            val adapter = notesAdapter(db, "fixture-instance")
            fun folder(id: String, parent: String) = """{"table_name":"folders","record_id":"$id","operation":"Insert","changed_at":"2023-11-15T00:00:00Z","data":{"id":"$id","parent_id":$parent,"title":"folder","created_at":1700000000000,"updated_at":1700000000000}}""".toByteArray()
            adapter.apply("peer", 1, listOf(folder("child", "\"parent\""), folder("parent", "null")))
            assertEquals("parent", db.query("SELECT parent_id FROM folders WHERE id='child'") { it.text(0) }.single())
        }
    }

    @Test fun unknownFieldVersionsAndNullSurviveRoundTrip() {
        DatabaseFactory.openShared(root.resolve("unknown.db"), SharedDatabase.VFS, source).use { db ->
            val adapter = notesAdapter(db, "fixture-instance")
            val extra = ",\"future\":9007199254740993,\"nothing\":null,\"__sync_field_deltas\":{\"future\":{\"hlc\":\"001700000000000-00001\",\"dev\":\"remote\",\"seq\":3}}"
            adapter.apply("peer", 1, listOf(resource("a", extra = extra)))
            val data = db.syncRead("resources", "a")!!
            assertEquals(JsonValue.integer(9007199254740993), data["future"])
            assertEquals(JsonValue.Null, data["nothing"])
            assertEquals(JsonValue.parseObject("""{"future":{"hlc":"001700000000000-00001","dev":"remote","seq":3}}"""), data["__sync_field_deltas"])
        }
    }

    @Test fun pendingKeepsDesktopMissingUpsertAndTombstoneWithoutChangingAcknowledgements() {
        DatabaseFactory.openLocal(root.resolve("pending-deleted.db")).use { db ->
            db.execute("CREATE TABLE resources(id TEXT PRIMARY KEY)")
            db.execute("CREATE TABLE __change_log(id INTEGER PRIMARY KEY,table_name TEXT,record_id TEXT,operation TEXT,changed_at TEXT,sync_version INTEGER,field_deltas_json TEXT)")
            db.execute("INSERT INTO __change_log VALUES(1,'resources','gone','INSERT','2026',0,NULL)")
            db.execute("INSERT INTO __change_log VALUES(2,'resources','gone','DELETE','2026',0,NULL)")
            val adapter = notesAdapter(db, "fixture-instance")
            val pending = adapter.pending().map { JsonValue.parseObject(it.decodeToString()) }
            assertEquals(listOf(JsonValue.text("INSERT"), JsonValue.text("DELETE")), pending.map { it["operation"] })
            assertEquals(listOf(JsonValue.Null, JsonValue.Null), pending.map { it["data"] })
            assertEquals(listOf(JsonValue.integer(1), JsonValue.integer(2)), pending.map { it["change_log_id"] })
            assertEquals(listOf(0L, 0L), db.query("SELECT sync_version FROM __change_log ORDER BY id") { it.long(0) })
        }
    }

    @Test fun dependencyRowDropsDeviceOwnedValuesFromUnknownVault() {
        DatabaseFactory.openShared(root.resolve("owned-unknown.db"), SharedDatabase.VFS, source).use { db ->
            val adapter = notesAdapter(db, "fixture-instance")
            adapter.apply("peer", 1, listOf(resource("a")))
            val stored = """{"index_error":"local-only","future":9007199254740993,"__sync_field_deltas":{"index_error":{"hlc":"x"},"future":{"hlc":"y"}}}"""
            db.execute("INSERT INTO __sync_unknown_fields(table_name,record_id,fields_json) VALUES('resources','a',?)",
                listOf(text(stored)))
            val row = db.readSnapshot { syncRead("resources", "a")!! }
            assertEquals(null, row["index_error"])
            assertEquals(JsonValue.integer(9007199254740993), row["future"])
            val deltas = row["__sync_field_deltas"] as JsonValue.Object
            assertEquals(null, deltas["index_error"])
            assertEquals(JsonValue.parseObject("""{"hlc":"y"}"""), deltas["future"])
            assertEquals(stored, db.query("SELECT fields_json FROM __sync_unknown_fields WHERE table_name='resources' AND record_id='a'") { it.text(0) }.single())
        }
    }

    @Test fun duplicateDoesNotReverifyBlob() {
        DatabaseFactory.openShared(root.resolve("blob.db"), SharedDatabase.VFS, source).use { db ->
            var available = true
            val adapter = notesAdapter(db, "fixture-instance", { _, _, _ -> available })
            val blob = """{"table_name":"blobs","record_id":"hash","operation":"Insert","changed_at":"2023-11-15T00:00:00Z","data":{"hash":"hash","relative_path":"hash","size":4,"created_at":1700000000000}}""".toByteArray()
            adapter.apply("peer", 1, listOf(blob))
            available = false
            adapter.apply("peer", 1, listOf(blob))
            assertEquals(1, adapter.cursor("peer"))
        }
    }

    @Test fun sameBatchDeleteAndRestoreSelectNewerVersion() {
        DatabaseFactory.openShared(root.resolve("batch-restore.db"), SharedDatabase.VFS, source).use { db ->
            val adapter = notesAdapter(db, "fixture-instance")
            fun row(time: Long) = """{"table_name":"resources","record_id":"r","operation":"Insert","changed_at":"$time","data":{"id":"r","hash":"h","type":"note","storage_mode":"inline","data":"x","created_at":1700000000000,"updated_at":$time,"deleted_at":null}}""".toByteArray()
            val delete = """{"table_name":"resources","record_id":"r","operation":"Delete","changed_at":"1700000001000","data":null}""".toByteArray()
            adapter.apply("peer", 1, listOf(row(1700000000000)))
            adapter.apply("peer", 2, listOf(delete, row(1700000002000)))
            assertEquals(1, db.query("SELECT deleted_at IS NULL FROM resources WHERE id='r'") { it.long(0) }.single())
        }
    }

    @Test fun deletesRejectStaleAndNewerRestoreWins() {
        DatabaseFactory.openShared(root.resolve("restore.db"), SharedDatabase.VFS, source).use { db ->
            val adapter = notesAdapter(db, "fixture-instance")
            fun change(operation: String, time: Long, data: String): ByteArray =
                """{"table_name":"resources","record_id":"r","operation":"$operation","changed_at":"$time","data":$data}""".toByteArray()
            fun row(time: Long) = """{"id":"r","hash":"h","type":"note","storage_mode":"inline","data":"x","created_at":1700000000000,"updated_at":$time,"deleted_at":null}"""
            adapter.apply("peer", 1, listOf(change("Insert", 1700000000000, row(1700000000000))))
            adapter.apply("peer", 2, listOf(change("Delete", 1700000001000, "null")))
            assertEquals(1700000001000, db.query("SELECT deleted_at FROM resources WHERE id='r'") { it.long(0) }.single())
            adapter.apply("peer", 3, listOf(change("Update", 1700000000000, row(1700000000000))))
            assertEquals(1700000001000, db.query("SELECT deleted_at FROM resources WHERE id='r'") { it.long(0) }.single())
            adapter.apply("peer", 4, listOf(change("Update", 1700000002000, row(1700000002000))))
            assertEquals(1, db.query("SELECT deleted_at IS NULL FROM resources WHERE id='r'") { it.long(0) }.single())
        }
    }

    @Test fun cancellationAfterFirstRowRollsBackTransaction() {
        DatabaseFactory.openShared(root.resolve("cancel-mid.db"), SharedDatabase.VFS, source).use { db ->
            var checks = 0
            var sawFirst = false
            val adapter = notesAdapter(db, "fixture-instance", checkCancelled = {
                checks++
                if (checks == 6) {
                    sawFirst = db.query("SELECT count(*) FROM resources WHERE id='a'") { it.long(0) }.single() == 1L
                    throw java.util.concurrent.CancellationException()
                }
            })
            assertThrows(java.util.concurrent.CancellationException::class.java) {
                adapter.apply("peer", 1, listOf(resource("a"), resource("b")))
            }
            assertEquals(true, sawFirst)
            assertEquals(0, adapter.cursor("peer"))
            assertEquals(0, db.query("SELECT count(*) FROM resources") { it.long(0) }.single())
            assertEquals(0, db.query("SELECT count(*) FROM __change_log") { it.long(0) }.single())
        }
    }

    @Test fun failedBatchAndInterruptedBatchRollBack() {
        DatabaseFactory.openShared(root.resolve("rollback.db"), SharedDatabase.VFS, source).use { db ->
            val adapter = notesAdapter(db, "fixture-instance")
            val bad = """{"table_name":"notes","record_id":"x","operation":"Insert","changed_at":"2026-11-15T00:00:00Z","data":{"id":"x"}}""".toByteArray()
            assertThrows(IllegalStateException::class.java) { adapter.apply("peer", 1, listOf(resource("ok"), bad)) }
            assertEquals(0, adapter.cursor("peer"))
            assertEquals(0, db.query("SELECT count(*) FROM resources") { it.long(0) }.single())
            try {
                Thread.currentThread().interrupt()
                assertThrows(java.util.concurrent.CancellationException::class.java) { adapter.apply("peer", 1, listOf(resource("ok"))) }
            } finally { Thread.interrupted() }
            assertEquals(0, adapter.cursor("peer"))
        }
    }

    @Test fun instanceCursorIsolation() {
        DatabaseFactory.openShared(root.resolve("instances.db"), SharedDatabase.VFS, source).use { db ->
            val first = notesAdapter(db, "first")
            val second = notesAdapter(db, "second")
            first.apply("peer", 2, emptyList())
            assertEquals(2, first.cursor("peer"))
            assertEquals(0, second.cursor("peer"))
        }
    }

    @Test fun missingUpsertIsSkippedOnlyWithRealSameTableAndKeyDelete() {
        DatabaseFactory.openShared(root.resolve("missing-image.db"), SharedDatabase.VFS, source).use { db ->
            var blobChecks = 0
            val adapter = notesAdapter(db, "fixture-instance", { _, _, _ -> blobChecks++; false })
            fun change(table: String, id: String, operation: String) =
                """{"table_name":"$table","record_id":"$id","operation":"$operation","changed_at":"2026-11-15T00:00:00Z","data":null}""".toByteArray()
            val missingInsert = change("blobs", "gone", "Insert")
            val missingUpdate = change("blobs", "gone", "Update")
            val matchingDelete = change("blobs", "gone", "Delete")
            adapter.apply("peer", 1, listOf(missingInsert, missingUpdate, matchingDelete))
            assertEquals(0, blobChecks)
            assertEquals(1L, adapter.cursor("peer"))
            assertEquals(0L, db.query("SELECT count(*) FROM blobs WHERE hash='gone'") { it.long(0) }.single())
            assertThrows(IllegalStateException::class.java) { adapter.apply("peer", 2, listOf(missingInsert)) }
            assertThrows(IllegalStateException::class.java) {
                adapter.apply("peer", 2, listOf(missingInsert, change("resources", "gone", "Delete")))
            }
            assertThrows(IllegalStateException::class.java) {
                adapter.apply("peer", 2, listOf(missingInsert, change("blobs", "another", "Delete")))
            }
            assertEquals(0, blobChecks)
            assertEquals(1L, adapter.cursor("peer"))
            assertEquals(0L, db.query("SELECT count(*) FROM __change_log WHERE COALESCE(sync_version,0)=0") { it.long(0) }.single())
        }
    }

    @Test fun missingBlobDoesNotAdvanceCursor() {
        DatabaseFactory.openShared(root.resolve("vfs.db"), SharedDatabase.VFS, source).use { db ->
            val adapter = notesAdapter(db, "fixture-instance")
            val blob = """{"table_name":"blobs","record_id":"bad","operation":"Insert","changed_at":"2026-11-15T00:00:00Z","data":{"hash":"bad","relative_path":"bad","size":4}}""".toByteArray()
            assertThrows(IllegalStateException::class.java) { adapter.apply("peer", 1, listOf(blob)) }
            assertEquals(0, adapter.cursor("peer"))
        }
    }

    @Test fun atomicApplyEchoUnknownAndReopen() {
        val file = root.resolve("vfs.db")
        DatabaseFactory.openShared(file, SharedDatabase.VFS, source).use { db ->
            val adapter = notesAdapter(db, "fixture-instance")
            adapter.apply("peer", 1, listOf(resource("a", extra = ",\"future\":9007199254740993")))
            assertEquals(1, adapter.cursor("peer"))
            assertEquals(0, adapter.pending().size)
            assertEquals(JsonValue.integer(9007199254740993), db.syncUnknown("resources", "a")["future"])
            adapter.apply("peer", 1, listOf(resource("a")))
            val invalid = """{"table_name":"unknown","record_id":"x","operation":"Insert","changed_at":"2026-11-15T00:00:00Z","data":{}}""".toByteArray()
            assertThrows(IllegalStateException::class.java) { adapter.apply("peer", 2, listOf(resource("b"), invalid)) }
            assertEquals(1, adapter.cursor("peer"))
            assertEquals(0, db.query("SELECT count(*) FROM resources WHERE id='b'") { it.long(0) }.single())
        }
        DatabaseFactory.openShared(file, SharedDatabase.VFS, source).use { db ->
            assertEquals(1, notesAdapter(db, "fixture-instance").cursor("peer"))
        }
    }
}

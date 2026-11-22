package com.deepstudent.app.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.deepstudent.app.storage.MainStorageResources
import com.deepstudent.core.common.id.Digests
import com.deepstudent.platform.database.BundledMigrationSource
import com.deepstudent.platform.database.DatabaseRegistry
import com.deepstudent.platform.database.SharedDatabase
import com.deepstudent.platform.storage.FileDataSpace
import java.io.File
import java.util.zip.ZipFile
import org.json.JSONObject
import org.junit.Assert.*

/** Compares the same ordered business projections as the frozen legacy-export verifier. */
internal class LegacyMobileReadbackAssertions(
    private val target: Context,
    private val scratch: File,
    private val seeds: JSONObject,
) {
    suspend fun verify(sourceZip: File, old: LegacyLocalState) {
        val active = FileDataSpace(target.filesDir).acquireActive().use { it.root.canonicalFile }
        assertNotEquals(old.root.canonicalFile, active)
        assertMigratedBeforeAnyBusinessOpen(active)
        compareBusinessRows(sourceZip, active)
        assertExpectedNotesAndBlob(active)
        assertLocalAndArchivedAudit(sourceZip, active, old.root)
        assertRepairWorks(active)
    }

    private fun assertMigratedBeforeAnyBusinessOpen(active: File) {
        readOnly(active.resolve("databases/vfs.db")).use { db ->
            db.rawQuery("SELECT version,name,checksum FROM refinery_schema_history ORDER BY version DESC LIMIT 1", null).use { rows ->
                assertTrue(rows.moveToFirst())
                assertEquals(20261120L, rows.getLong(0))
                assertEquals("fix_plugin_delete_trigger", rows.getString(1))
                assertEquals("17799307631992323381", rows.getString(2))
                assertFalse(rows.moveToNext())
            }
            db.rawQuery("SELECT sql FROM sqlite_master WHERE type='trigger' AND name='trg__change_log_portable_plugin_records_delete'", null)
                .use { rows ->
                    assertTrue(rows.moveToFirst())
                    val sql = rows.getString(0)
                    assertTrue(sql.contains("OLD.id", ignoreCase = true))
                    assertFalse(sql.contains("NEW.id", ignoreCase = true))
                    assertFalse(rows.moveToNext())
                }
        }
    }

    private fun compareBusinessRows(sourceZip: File, active: File) {
        ZipFile(sourceZip).use { zip ->
            for (id in SharedDatabase.entries) {
                val source = scratch.resolve("source-${id.id}.db")
                zip.getInputStream(checkNotNull(zip.getEntry("${id.id}.db"))).use { input ->
                    source.outputStream().use { output -> input.copyTo(output) }
                }
                readOnly(source).use { original ->
                    DatabaseRegistry.openShared(active.resolve("databases/${id.id}.db"), id, BundledMigrationSource(target)).use { restored ->
                        val report = checkNotNull(restored.migrationReport)
                        assertEquals("migration must already have completed before slot activation", 0, report.appliedCount)
                        for (projection in projections.filter { it.database == id }) {
                            val before = original.rawQuery(projection.sql, null).use { cursor ->
                                val rows = mutableListOf<List<String?>>()
                                while (cursor.moveToNext()) rows += (0 until cursor.columnCount).map { cursor.getString(it) }
                                rows
                            }
                            assertEquals(projection.name, projection.count, before.size)
                            val after = restored.query(projection.sql) { row ->
                                (0 until projection.columns).map { index -> if (row.isNull(index)) null else row.text(index) }
                            }
                            assertEquals(projection.name, before, after)
                        }
                    }
                }
            }
        }
    }

    private suspend fun assertExpectedNotesAndBlob(active: File) {
        MainStorageResources.open(target).use { storage ->
            val ids = seeds.getJSONArray("noteIds")
            val titles = seeds.getJSONArray("noteTitles")
            val contents = seeds.getJSONArray("noteContents")
            val hashes = seeds.getJSONArray("noteHashes")
            for (i in 0 until ids.length()) {
                val id = ids.getString(i)
                val note = checkNotNull(storage.notes.get(id))
                assertEquals(titles.getString(i), note.title)
                assertEquals(contents.getString(i), note.content)
                assertEquals(hashes.getString(i), Digests.sha256HexUtf8("$id:${note.content}"))
            }
        }
        val file = active.resolve("vfs_blobs/${seeds.getString("blobRelativePath")}")
        val bytes = file.readBytes()
        assertEquals(seeds.getInt("fileBytes"), bytes.size)
        assertArrayEquals(seeds.getString("fileText").toByteArray(Charsets.UTF_8), bytes)
        assertEquals(seeds.getString("fileSha256"), Digests.sha256Hex(bytes))
    }

    private fun assertLocalAndArchivedAudit(sourceZip: File, active: File, oldRoot: File) {
        for (root in listOf(oldRoot, active)) {
            DatabaseRegistry.openLocal(root.resolve("databases/audit.db")).use { local ->
                val rows = local.query("SELECT id,value FROM local_audit") { listOf(it.text(0), it.text(1)) }
                assertEquals(listOf(listOf("local-only", "never-replaced")), rows)
            }
        }
        val archived = active.resolve("legacy-import/audit.db")
        assertTrue(archived.isFile)
        ZipFile(sourceZip).use { zip ->
            val original = zip.getInputStream(checkNotNull(zip.getEntry("databases/audit.db"))).use { it.readBytes() }
            assertArrayEquals(original, archived.readBytes())
        }
        readOnly(archived).use { audit ->
            audit.rawQuery("SELECT id,operation_type,target,status FROM __audit_log ORDER BY id", null).use { rows ->
                assertTrue(rows.moveToFirst())
                assertEquals(listOf("audit_legacy_demo", "Migration", "vfs", "Completed"), (0..3).map(rows::getString))
                assertFalse(rows.moveToNext())
            }
        }
    }

    private fun assertRepairWorks(active: File) {
        val record = "[\"plugin.legacy\",\"state\",\"repair-probe\"]"
        DatabaseRegistry.openShared(active.resolve("databases/vfs.db"), SharedDatabase.VFS, BundledMigrationSource(target)).use { db ->
            db.transaction {
                execute("""INSERT INTO portable_plugin_records(id,owner_id,owner_kind,namespace,record_id,package_version,
                    source_hash,schema_version,min_writer_schema,data_json,unknown_json,deleted_at,device_id,local_version,updated_at)
                    VALUES (json_array('plugin.legacy','state','repair-probe'),'plugin.legacy','plugin','state','repair-probe','1',
                    '${"a".repeat(64)}',1,1,'{}','{}',NULL,'fixture',1,'2026-01-01')""".trimIndent())
                execute("DELETE FROM portable_plugin_records WHERE id=json_array('plugin.legacy','state','repair-probe')")
            }
            val deletions = db.query("SELECT record_id FROM __change_log WHERE table_name='portable_plugin_records' AND operation='DELETE' ORDER BY id") {
                it.text(0)
            }
            assertEquals(listOf(record), deletions)
        }
    }

    private fun readOnly(file: File): SQLiteDatabase =
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
}

private data class LegacyProjection(
    val name: String, val database: SharedDatabase, val sql: String, val columns: Int, val count: Int,
)

private val projections = listOf(
    LegacyProjection("notes", SharedDatabase.VFS, "SELECT id,resource_id,title,tags FROM notes ORDER BY id", 4, 2),
    LegacyProjection("note formats", SharedDatabase.VFS, "SELECT note_id,content_format,format_version,serializer_version FROM note_document_formats ORDER BY note_id", 4, 2),
    LegacyProjection("note revisions", SharedDatabase.VFS, "SELECT version_id,note_id,source,content_format,content_md FROM note_document_revisions ORDER BY seq", 5, 2),
    LegacyProjection("resources", SharedDatabase.VFS, "SELECT id,hash,type,source_id,source_table,storage_mode,ref_count,data FROM resources ORDER BY id", 8, 3),
    LegacyProjection("blobs", SharedDatabase.VFS, "SELECT hash,relative_path,size,mime_type,ref_count FROM blobs ORDER BY hash", 5, 1),
    LegacyProjection("files", SharedDatabase.VFS, "SELECT id,resource_id,blob_hash,sha256,file_name,size,type,mime_type FROM files ORDER BY id", 8, 1),
    LegacyProjection("folders", SharedDatabase.VFS, "SELECT id,title FROM folders ORDER BY id", 2, 1),
    LegacyProjection("folder references", SharedDatabase.VFS, "SELECT folder_id,item_type,item_id FROM folder_items ORDER BY item_type,item_id", 3, 3),
    LegacyProjection("note links", SharedDatabase.VFS, "SELECT source_id,position,target_id,target_title,link_type FROM note_links ORDER BY source_id,position", 5, 1),
    LegacyProjection("chat sessions", SharedDatabase.CHAT_V2, "SELECT id,mode,title,persist_status,created_at,updated_at FROM chat_v2_sessions ORDER BY id", 6, 1),
    LegacyProjection("chat messages", SharedDatabase.CHAT_V2, "SELECT id,session_id,role,block_ids_json,timestamp,parent_id FROM chat_v2_messages ORDER BY id", 6, 2),
    LegacyProjection("chat blocks", SharedDatabase.CHAT_V2, "SELECT id,message_id,block_type,status,block_index,content FROM chat_v2_blocks ORDER BY id", 6, 1),
    LegacyProjection("mistakes", SharedDatabase.MISTAKES, "SELECT id,mistake_type,status,ocr_text FROM mistakes ORDER BY id", 4, 1),
    LegacyProjection("mistake messages", SharedDatabase.MISTAKES, "SELECT mistake_id,role,content,timestamp FROM chat_messages ORDER BY id", 4, 1),
    LegacyProjection("document tasks", SharedDatabase.MISTAKES, "SELECT id,document_id,status,segment_index FROM document_tasks ORDER BY id", 4, 1),
    LegacyProjection("anki cards", SharedDatabase.MISTAKES, "SELECT id,task_id,front,back,tags_json,source_type,source_id FROM anki_cards ORDER BY id", 7, 1),
    LegacyProjection("settings in mistakes", SharedDatabase.MISTAKES, "SELECT key,value,updated_at FROM settings ORDER BY key", 3, 2),
    LegacyProjection("usage logs", SharedDatabase.LLM_USAGE, "SELECT id,provider,model,caller_type,prompt_tokens,completion_tokens,total_tokens,status FROM llm_usage_logs ORDER BY id", 8, 1),
    LegacyProjection("usage daily", SharedDatabase.LLM_USAGE, "SELECT date,caller_type,model,provider,request_count,total_tokens FROM llm_usage_daily ORDER BY date", 6, 1),
)

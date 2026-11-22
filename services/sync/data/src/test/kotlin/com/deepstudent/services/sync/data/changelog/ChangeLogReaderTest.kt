package com.deepstudent.services.sync.data.changelog

import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.platform.database.DatabaseFactory
import com.deepstudent.platform.database.SqlDatabase
import com.deepstudent.platform.database.SqlValue
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ChangeLogReaderTest {
    private val root = Files.createTempDirectory("sync-changelog-").toFile()
    @After fun cleanup() { root.deleteRecursively() }

    @Test fun signedIdsBoundedPagesAndRowsStayOnOneWalSnapshot() = database { db ->
        db.execute("CREATE TABLE resources(id TEXT PRIMARY KEY, data TEXT)")
        db.execute("INSERT INTO resources VALUES('r','before')")
        addLog(db, Long.MIN_VALUE, "resources", "r", "INSERT")
        addLog(db, -1, "resources", "r", "UPDATE", "2029")
        addLog(db, 9007199254740993L, "resources", "r", "DELETE", "2020")
        val reader = reader(db, "resources" to listOf("id"))
        val executor = Executors.newSingleThreadExecutor()
        try {
            reader.withSnapshot { _, snapshot ->
                assertEquals(9007199254740993L, snapshot.highWaterId)
                val first = snapshot.page(limit = 2)
                assertEquals(listOf(Long.MIN_VALUE, -1L), ids(first.changes))
                assertTrue(first.hasMore)
                executor.submit {
                    db.transaction {
                        execute("UPDATE resources SET data='after' WHERE id='r'")
                        addLog(this, 9007199254740994L, "resources", "r", "UPDATE")
                    }
                }.get(10, TimeUnit.SECONDS)
                val second = snapshot.page(first.nextAfterId, 2)
                assertEquals(listOf(9007199254740993L), ids(second.changes))
                assertEquals(JsonValue.Null, obj(second.changes.single())["data"])
                assertFalse(second.hasMore)
                assertEquals("before", data(first.changes.first())["data"].asText())
                assertEquals("before", data(snapshot.page(limit = 1).changes.single())["data"].asText())
            }
            val next = reader.readAll()
            assertEquals(4, next.size)
            assertEquals("after", data(next.first())["data"].asText())
            assertEquals(4L, db.query("SELECT count(*) FROM __change_log WHERE COALESCE(sync_version,0)=0") { it.long(0) }.single())
        } finally { executor.shutdownNow() }
    }

    @Test fun emptySnapshotCannotEscapeOrMoveThreads() = database { db ->
        val reader = reader(db)
        lateinit var escaped: ChangeLogSnapshot
        val executor = Executors.newSingleThreadExecutor()
        try {
            reader.withSnapshot { _, snapshot ->
                escaped = snapshot
                assertNull(snapshot.highWaterId)
                assertTrue(snapshot.page().changes.isEmpty())
                val rejected = executor.submit<Boolean> {
                    try { snapshot.page(); false } catch (_: IllegalStateException) { true }
                }.get(10, TimeUnit.SECONDS)
                assertTrue(rejected)
            }
            assertThrows(IllegalStateException::class.java) { escaped.page() }
            assertThrows(IllegalStateException::class.java) { escaped.highWaterId }
        } finally { executor.shutdownNow() }
    }

    @Test fun tombstonesKeepOperationsAndCurrentImagesAcrossDeleteAndReinsert() = database { db ->
        db.execute("CREATE TABLE resources(id TEXT PRIMARY KEY, data TEXT)")
        addLog(db, 1, "resources", "missing", "INSERT")
        addLog(db, 2, "resources", "missing", "UPDATE")
        addLog(db, 3, "resources", "missing", "DELETE")
        db.execute("INSERT INTO resources VALUES('reinsert','new')")
        addLog(db, 4, "resources", "reinsert", "INSERT")
        addLog(db, 5, "resources", "reinsert", "DELETE")
        addLog(db, 6, "resources", "reinsert", "INSERT")
        val records = reader(db, "resources" to listOf("id")).readAll().map(::obj)
        assertEquals(listOf("Insert", "Update", "Delete", "Insert", "Delete", "Insert"), records.map { it["operation"].asText() })
        assertTrue(records.take(3).all { it["data"] == JsonValue.Null })
        assertEquals("new", (records[3]["data"] as JsonValue.Object)["data"].asText())
        assertEquals(JsonValue.Null, records[4]["data"])
        assertEquals("new", (records[5]["data"] as JsonValue.Object)["data"].asText())
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L, 6L), records.map { (it["change_log_id"] as JsonValue.Number).asLongOrNull() })
    }

    @Test fun physicalPluginDeletesKeepTheOldKeyAndNeverReadTheReinsertedImage() = database { db ->
        db.execute("CREATE TABLE portable_plugin_records(id TEXT PRIMARY KEY, data_json TEXT, deleted_at TEXT, local_version INTEGER)")
        for (operation in listOf("INSERT", "UPDATE", "DELETE")) {
            val image = if (operation == "DELETE") "OLD" else "NEW"
            db.execute("""CREATE TRIGGER capture_${operation.lowercase()} AFTER $operation ON portable_plugin_records
                BEGIN INSERT INTO __change_log(table_name,record_id,operation,changed_at,sync_version)
                VALUES('portable_plugin_records',$image.id,'$operation','2026',0); END""")
        }
        // Keep the sentinel published, so trigger-generated ids exercise integers above 2^53.
        db.execute("INSERT INTO __change_log VALUES(9007199254740992,'sentinel','ignored','INSERT','2026',1,NULL)")
        val sameId = """["plugin.s2","state","reinsert"]"""
        val removedId = """["plugin.s2","state","removed"]"""
        fun insert(id: String, value: String) = db.execute("INSERT INTO portable_plugin_records VALUES(?,?,NULL,9007199254740993)",
            listOf(SqlValue.Text(id), SqlValue.Text(value)))
        insert(sameId, "before")
        db.execute("UPDATE portable_plugin_records SET deleted_at='2026-01-02' WHERE id=?", listOf(SqlValue.Text(sameId)))
        db.execute("DELETE FROM portable_plugin_records WHERE id=?", listOf(SqlValue.Text(sameId)))
        insert(sameId, "after")
        db.execute("UPDATE portable_plugin_records SET data_json='current' WHERE id=?", listOf(SqlValue.Text(sameId)))
        insert(removedId, "gone")
        db.execute("DELETE FROM portable_plugin_records WHERE id=?", listOf(SqlValue.Text(removedId)))

        val reader = reader(db, "portable_plugin_records" to listOf("id"))
        val changes = reader.readAll().map(::obj)
        assertEquals(listOf("Insert", "Update", "Delete", "Insert", "Update", "Insert", "Delete"),
            changes.map { it["operation"].asText() })
        assertEquals(List(5) { sameId } + List(2) { removedId }, changes.map { it["record_id"].asText() })
        assertEquals((9007199254740993L..9007199254740999L).toList(),
            changes.map { (it["change_log_id"] as JsonValue.Number).asLongOrNull() })
        for (index in listOf(0, 1, 3, 4)) {
            val data = changes[index]["data"] as JsonValue.Object
            assertEquals("current", data["data_json"].asText())
            assertEquals(JsonValue.Null, data["deleted_at"])
            assertEquals(9007199254740993L, (data["local_version"] as JsonValue.Number).asLongOrNull())
        }
        assertTrue(listOf(2, 5, 6).all { changes[it]["data"] == JsonValue.Null })
        assertEquals(7L, db.query("SELECT count(*) FROM __change_log WHERE sync_version=0") { it.long(0) }.single())
    }

    @Test fun exactStorageValuesOpaqueFieldsAndOwnershipArePreserved() = database { db ->
        db.execute("CREATE TABLE resources(id TEXT PRIMARY KEY, big INTEGER, minval INTEGER, maxval INTEGER, \"nothing\" TEXT, empty TEXT, index_error TEXT, payload BLOB, realnum REAL)")
        db.execute("CREATE TABLE __sync_unknown_fields(table_name TEXT, record_id TEXT, fields_json TEXT, PRIMARY KEY(table_name,record_id))")
        db.execute("INSERT INTO resources VALUES('r',9007199254740993,-9223372036854775808,9223372036854775807,NULL,'','private',X'00FF',1.0)")
        val vault = """{"future":{"hlc":"h","dev":"d","seq":9007199254740993,"value":null},"unknown":"","big":7,"index_error":"private","__sync_field_deltas":{"index_error":{"hlc":"no"},"opaque":{"hlc":"yes"}}}"""
        db.execute("INSERT INTO __sync_unknown_fields VALUES('resources','r',?)", listOf(SqlValue.Text(vault)))
        addLog(db, 1, "resources", "r", "UPDATE", delta = """{"index_error":{"dev":"d"},"visible":{"hlc":"v"}}""")
        val before = db.query("SELECT fields_json FROM __sync_unknown_fields") { it.text(0) }.single()
        val row = data(reader(db, "resources" to listOf("id")).readAll().single())
        assertEquals("9007199254740993", (row["big"] as JsonValue.Number).literal)
        assertEquals(Long.MIN_VALUE, (row["minval"] as JsonValue.Number).asLongOrNull())
        assertEquals(Long.MAX_VALUE, (row["maxval"] as JsonValue.Number).asLongOrNull())
        assertEquals(JsonValue.Null, row["nothing"])
        assertEquals("", row["empty"].asText())
        assertEquals("", row["unknown"].asText())
        assertEquals(JsonValue.Null, row["future"])
        assertNull(row["index_error"])
        assertEquals("AP8=", (row["payload"] as JsonValue.Object)["\$dsblob"].asText())
        assertEquals("1.0", (row["realnum"] as JsonValue.Number).literal)
        val versions = row["__sync_field_deltas"] as JsonValue.Object
        assertNull(versions["index_error"])
        assertNull(versions["future"]) // Desktop readUnknownFields unwraps value; it does not synthesize clock deltas.
        assertNotNull(versions["visible"])
        assertNotNull(versions["opaque"])
        assertEquals(before, db.query("SELECT fields_json FROM __sync_unknown_fields") { it.text(0) }.single())
    }

    @Test fun notesCompatibilityPreservesVaultClocksAndPrefersExplicitLogDeltas() = database { db ->
        db.execute("CREATE TABLE resources(id TEXT PRIMARY KEY, title TEXT, index_error TEXT)")
        db.execute("CREATE TABLE __sync_unknown_fields(table_name TEXT, record_id TEXT, fields_json TEXT, PRIMARY KEY(table_name,record_id))")
        db.execute("INSERT INTO resources VALUES('r','current','local')")
        val vault = """{"future":{"value":null,"hlc":"h1","dev":"d","seq":9007199254740993},"title":"stale","index_error":{"value":"private","hlc":"h1","dev":"d"}}"""
        db.execute("INSERT INTO __sync_unknown_fields VALUES('resources','r',?)", listOf(SqlValue.Text(vault)))
        addLog(db, 1, "resources", "r", "UPDATE")
        addLog(db, 2, "resources", "r", "UPDATE", delta = """{"future":{"hlc":"h2","dev":"other","seq":9007199254740994}}""")
        val reader = ChangeLogReader(db, "vfs", { if (it == "resources") listOf("id") else null }, preserveVaultClocks = true)
        val rows = reader.readAll().map(::data)
        assertTrue(rows.all { it["future"] == JsonValue.Null && it["title"] == JsonValue.text("current") && it["index_error"] == null })
        val clocks = rows.map { (it["__sync_field_deltas"] as JsonValue.Object).asMap() }
        assertTrue(clocks.all { it.keys == setOf("future") })
        assertEquals(JsonValue.parseObject("""{"hlc":"h1","dev":"d","seq":9007199254740993}"""), clocks[0]["future"])
        assertEquals(JsonValue.parseObject("""{"hlc":"h2","dev":"other","seq":9007199254740994}"""), clocks[1]["future"])
    }

    @Test fun nullDeltasAreOmittedAndMalformedDeleteDeltasStillFail() = database { db ->
        db.execute("CREATE TABLE resources(id TEXT PRIMARY KEY)")
        db.execute("INSERT INTO resources VALUES('r')")
        addLog(db, 1, "resources", "r", "UPDATE", delta = "null")
        val reader = reader(db, "resources" to listOf("id"))
        assertNull(data(reader.readAll().single())["__sync_field_deltas"])
        addLog(db, 2, "resources", "r", "DELETE", delta = "{")
        assertThrows(RuntimeException::class.java) { reader.readAll() }
        assertEquals(listOf(0L, 0L), db.query("SELECT sync_version FROM __change_log ORDER BY id") { it.long(0) })
    }

    @Test fun registryKeyAndCompatibilityFormsHandleCompositeAndQuestionsFallback() = database { db ->
        db.execute("CREATE TABLE tags(session_id TEXT,tag TEXT, value TEXT,PRIMARY KEY(session_id,tag))")
        db.execute("INSERT INTO tags VALUES('s','t','row')")
        val keys = listOf("""{"session_id":"s","tag":"t"}""", """["s","t"]""", "s:t")
        keys.forEachIndexed { i, key -> addLog(db, i.toLong() + 1, "tags", key, "UPDATE") }
        db.execute("CREATE TABLE questions(id TEXT PRIMARY KEY, exam_id TEXT, text TEXT)")
        db.execute("INSERT INTO questions VALUES('q','exam','hello')")
        addLog(db, 4, "questions", "exam", "UPDATE")
        val records = reader(db, "tags" to listOf("session_id", "tag"), "questions" to listOf("id")).readAll()
        assertEquals(listOf("row", "row", "row"), records.take(3).map { data(it)["value"].asText() })
        assertEquals("q", data(records[3])["id"].asText())
        db.execute("INSERT INTO questions VALUES('q2','exam','another')")
        assertEquals(JsonValue.Null, obj(reader(db, "tags" to listOf("session_id", "tag"), "questions" to listOf("id")).readAll()[3])["data"])
    }

    @Test fun incorrectRegistryMissingKeysAndLimitsFailClosed() = database { db ->
        db.execute("CREATE TABLE resources(id TEXT PRIMARY KEY)")
        addLog(db, 1, "resources", "r", "UPDATE")
        assertThrows(IllegalArgumentException::class.java) { reader(db).readAll() }
        assertThrows(IllegalArgumentException::class.java) { reader(db, "resources" to listOf("nonexistent")).readAll() }
        reader(db, "resources" to listOf("id")).withSnapshot { _, snapshot ->
            assertThrows(IllegalArgumentException::class.java) { snapshot.page(limit = 0) }
            assertThrows(IllegalArgumentException::class.java) { snapshot.page(limit = 501) }
        }
        assertEquals(0L, db.query("SELECT COALESCE(sync_version,0) FROM __change_log WHERE id=1") { it.long(0) }.single())
    }

    @Test fun specialDailyKeyFormatsAndLongKeyTokensRemainExact() {
        val columns = listOf("date", "caller_type", "model", "provider")
        assertEquals(listOf("2026-01-01", "chat", "m", "provider_name"),
            parseRecordKey("llm_usage_daily", "2026-01-01_chat_m_provider_name", columns))
        assertEquals(listOf("2026", "chat", "9007199254740993", "p"),
            parseRecordKey("x", """{"date":"2026","caller_type":"chat","model":9007199254740993,"provider":"p"}""", columns))
    }

    private fun database(block: (SqlDatabase) -> Unit) {
        DatabaseFactory.openLocal(root.resolve("db")).use { db ->
            db.execute("CREATE TABLE __change_log(id INTEGER PRIMARY KEY, table_name TEXT, record_id TEXT, operation TEXT, changed_at TEXT, sync_version INTEGER, field_deltas_json TEXT)")
            block(db)
        }
    }
    private fun reader(db: SqlDatabase, vararg keys: Pair<String, List<String>>): ChangeLogReader {
        val map = keys.toMap()
        return ChangeLogReader(db, "vfs", { map[it] })
    }
    private fun addLog(db: com.deepstudent.platform.database.SqlOperations, id: Long, table: String, record: String,
        operation: String, changedAt: String = "2026", delta: String? = null) {
        db.execute("INSERT INTO __change_log VALUES(?,?,?,?,?,0,?)", listOf(SqlValue.Integer(id), SqlValue.Text(table),
            SqlValue.Text(record), SqlValue.Text(operation), SqlValue.Text(changedAt), delta?.let(SqlValue::Text) ?: SqlValue.Null))
    }
    private fun ids(rows: List<ByteArray>) = rows.map { (obj(it)["change_log_id"] as JsonValue.Number).asLongOrNull() }
    private fun obj(bytes: ByteArray) = JsonValue.parseObject(bytes.decodeToString())
    private fun data(bytes: ByteArray) = obj(bytes)["data"] as JsonValue.Object
    private fun JsonValue?.asText() = (this as JsonValue.Text).value
}

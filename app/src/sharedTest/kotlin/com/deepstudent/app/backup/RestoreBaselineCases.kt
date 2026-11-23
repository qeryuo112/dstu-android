package com.deepstudent.app.backup

import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.platform.database.SharedDatabase
import com.deepstudent.platform.database.SqlDatabase
import com.deepstudent.services.backup.data.CommitDisposition
import com.deepstudent.services.backup.data.RestoreError
import com.deepstudent.services.backup.data.RestoreExecutionResult
import com.deepstudent.services.backup.data.RestorePhase
import com.deepstudent.services.sync.data.changelog.ChangeLogReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import java.io.File

internal class RestoreBaselineCases(private val root: File, private val createFixture: (File) -> RestoreSqlFixture) {
    fun fourDatabaseRestore() {
        val host = createFixture(root)
        host.original.resolve("databases").mkdir()
        SharedDatabase.entries.forEach { id ->
            host.incoming(id) {
                seed(it, id, "from-backup")
                seedBaselineState(it)
            }
            host.open(host.original, id).use {
                seed(it, id, "old-active")
                seedBaselineState(it)
            }
        }
        val zip = host.archive()
        val trace = mutableListOf<SharedDatabase>()
        val verifier = { plan: com.deepstudent.services.backup.api.RestorePlan ->
            ordinaryRestoreVerifier(host.migrations, plan, baselineLog = { id, stats, _ ->
                assertTrue(checkNotNull(stats).clearedChanges > 0)
                trace.add(id)
            })
        }
        assertEquals(RestoreExecutionResult.Committed("host-restore", CommitDisposition.NEWLY_COMMITTED), host.restore(zip, verifier))
        assertEquals(SharedDatabase.entries.toList(), trace)
        assertNotEquals(host.original, host.active)
        SharedDatabase.entries.forEach { id ->
            host.open(host.original, id).use { db ->
                assertEquals(listOf("old-active"), contents(db, id))
                assertBaselineState(db, version = 1, rows = 1)
                assertTrue(
                    db.query(
                        "SELECT 1 FROM __change_log WHERE table_name=? AND sync_version=0 LIMIT 1",
                        listOf(com.deepstudent.platform.database.SqlValue.Text(table(id))),
                    ) { it.long(0) }.isNotEmpty(),
                )
            }
            host.open(host.active, id).use { db ->
                assertEquals(listOf("from-backup"), contents(db, id))
                assertBaselineState(db, version = 7, rows = 0)
                assertEquals(0L, db.query("SELECT count(*) FROM __change_log") { it.long(0) }.single())
                assertTrue("old pending in ${id.id}", reader(db, id).readAll().isEmpty())
                edit(db, id)
            }
        }
        assertEquals(
            RestoreExecutionResult.Committed("host-restore", CommitDisposition.ALREADY_ACTIVE),
            host.reopen().restore(zip, verifier),
        )
        assertEquals(SharedDatabase.entries.toList(), trace)
        SharedDatabase.entries.forEach { id ->
            host.open(host.active, id).use { db ->
                val changes = reader(db, id).readAll().map { JsonValue.parseObject(it.decodeToString()) }
                assertEquals(listOf("edited"), contents(db, id))
                assertEquals(listOf("Insert", "Update", "Delete"), changes.map { (it["operation"] as JsonValue.Text).value })
                assertTrue(changes.all { it["database_name"] == JsonValue.text(id.id) })
            }
        }
    }

    fun failedRestoreCanRetry() {
        val host = createFixture(root)
        host.original.resolve("keep").writeText("original")
        val last = SharedDatabase.entries.last()
        SharedDatabase.entries.forEach { id ->
            host.incoming(id) { db ->
                seed(db, id, "incoming")
                if (id == last) db.execute("CREATE VIEW __sync_conflicts AS SELECT 'old' AS id")
            }
        }
        val result = host.restore(host.archive())
        assertEquals(RestoreExecutionResult.Failed("host-restore", RestorePhase.VERIFY_DATABASES, RestoreError.DATABASE_INVALID), result)
        assertEquals(host.original, host.active)
        assertEquals("original", host.active.resolve("keep").readText())
        host.incoming(last) { it.execute("DROP VIEW __sync_conflicts") }
        assertEquals(RestoreExecutionResult.Committed("host-restore", CommitDisposition.NEWLY_COMMITTED), host.restore(host.archive()))
        assertNotEquals(host.original, host.active)
    }

    private fun seedBaselineState(db: SqlDatabase) = db.transaction {
        execute("CREATE TABLE restore_baseline_probe(content TEXT,local_version INTEGER,sync_version INTEGER)")
        execute("INSERT INTO restore_baseline_probe VALUES('unchanged',7,1)")
        for (table in listOf("__sync_id_aliases", "__sync_conflicts")) {
            execute("CREATE TABLE IF NOT EXISTS $table (id TEXT)")
            execute("INSERT INTO $table VALUES('old')")
        }
    }

    private fun assertBaselineState(db: SqlDatabase, version: Long, rows: Long) {
        assertEquals(
            listOf(Triple("unchanged", 7L, version)),
            db.query(
                "SELECT content,local_version,sync_version FROM restore_baseline_probe",
            ) { Triple(it.text(0), it.long(1), it.long(2)) },
        )
        for (table in listOf("__sync_id_aliases", "__sync_conflicts")) {
            assertEquals(rows, db.query("SELECT count(*) FROM $table") { it.long(0) }.single())
        }
    }

    private fun table(id: SharedDatabase) = when (id) {
        SharedDatabase.VFS -> "resources"
        SharedDatabase.CHAT_V2 -> "chat_v2_sessions"
        SharedDatabase.MISTAKES -> "custom_anki_templates"
        SharedDatabase.LLM_USAGE -> "llm_usage_logs"
    }

    private fun column(id: SharedDatabase) = when (id) {
        SharedDatabase.VFS -> "hash"
        SharedDatabase.CHAT_V2 -> "title"
        SharedDatabase.MISTAKES -> "name"
        SharedDatabase.LLM_USAGE -> "model"
    }

    private fun seed(db: SqlDatabase, id: SharedDatabase, content: String, key: String = "restored") = db.execute(
        when (id) {
            SharedDatabase.VFS -> "INSERT INTO resources(id,hash,type,created_at,updated_at) VALUES('$key','$content','note',1,1)"

            SharedDatabase.CHAT_V2 ->
                "INSERT INTO chat_v2_sessions(id,mode,title,created_at,updated_at) " +
                    "VALUES('$key','general_chat','$content','a','a')"

            SharedDatabase.MISTAKES ->
                "INSERT INTO custom_anki_templates(" +
                    "id,name,preview_front,preview_back,generation_prompt,front_template,back_template,css_style) " +
                    "VALUES('$key','$content','f','b','p','f','b','c')"

            SharedDatabase.LLM_USAGE ->
                "INSERT INTO llm_usage_logs(id,timestamp,provider,model,caller_type) " +
                    "VALUES('$key','2026-01-01T00:00:00Z','p','$content','other')"
        },
    )

    private fun contents(db: SqlDatabase, id: SharedDatabase) =
        db.query("SELECT ${column(id)} FROM ${table(id)} ORDER BY id") { it.text(0) }

    private fun reader(db: SqlDatabase, id: SharedDatabase) =
        ChangeLogReader(db, id.id, { name -> if (name == table(id)) listOf("id") else null })

    private fun edit(db: SqlDatabase, id: SharedDatabase) {
        seed(db, id, "new", "transient")
        db.execute("UPDATE ${table(id)} SET ${column(id)}='edited' WHERE id='restored'")
        db.execute("DELETE FROM ${table(id)} WHERE id='transient'")
    }
}

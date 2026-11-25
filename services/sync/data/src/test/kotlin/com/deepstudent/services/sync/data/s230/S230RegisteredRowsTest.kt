package com.deepstudent.services.sync.data.s230

import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.platform.database.SharedDatabase
import com.deepstudent.platform.database.SqlDatabase
import com.deepstudent.platform.database.SqlValue
import com.deepstudent.services.sync.data.apply.IncomingDomain
import com.deepstudent.services.sync.data.apply.RegisteredIncomingRows
import com.deepstudent.services.sync.data.apply.RegisteredSqlRowApplier
import com.deepstudent.services.sync.data.changelog.ChangeLogReader
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.file.Files

class S230RegisteredRowsTest {
    private val root = Files.createTempDirectory("s230-registered-").toFile()

    @After fun cleanup() {
        root.deleteRecursively()
    }

    @Test fun productionRowApplierUsesFrozenKeysAndPreservesExactIntegersAndUnknownClocks() {
        S230MigratedFixture(root, s230HostMigrationSource()).use { f ->
            val db = f.db(SharedDatabase.CHAT_V2)
            val applier = chatApplier(f)
            val row = session("2026-01-01T00:00:00Z", "remote", "9007199254740993")
            applier.validate(listOf(row))
            db.transaction { applier.apply(this, listOf(row)) }
            assertEquals("remote", db.query("SELECT title FROM chat_v2_sessions WHERE id='session'") { it.text(0) }.single())
            val statement = "SELECT fields_json FROM __sync_unknown_fields WHERE table_name='chat_v2_sessions' AND record_id='session'"
            val vault = db.query(statement) { it.text(0) }.single()
            assertEquals(JsonValue.integer(9007199254740993L), (JsonValue.parseObject(vault)["future"] as JsonValue.Object)["value"])
            db.execute("UPDATE chat_v2_sessions SET title='local edit',updated_at='2026-01-02T00:00:00Z' WHERE id='session'")
            db.transaction { applier.apply(this, listOf(session("2025-01-01T00:00:00Z", "stale", "9007199254740992"))) }
            assertEquals("local edit", db.query("SELECT title FROM chat_v2_sessions WHERE id='session'") { it.text(0) }.single())
            val keys = f.registry.rowSyncKeys(SharedDatabase.CHAT_V2)
            val pending = ChangeLogReader(db, SharedDatabase.CHAT_V2.id, keys::get, preserveVaultClocks = true).readAll()
            val captured = pending.map { JsonValue.parseObject(it.decodeToString()) }
                .last { (it["table_name"] as JsonValue.Text).value == "chat_v2_sessions" }
            val data = captured["data"] as JsonValue.Object
            assertEquals(JsonValue.integer(9007199254740993L), data["future"])
            assertEquals(JsonValue.text("local edit"), data["title"])
        }
    }

    @Test fun registrationRejectsBackupOnlyAndMismatchedRecordKeysBeforeWriting() {
        S230MigratedFixture(root, s230HostMigrationSource()).use { f ->
            assertThrows(IllegalArgumentException::class.java) {
                val domain = IncomingDomain(SharedDatabase.LLM_USAGE, "usage", listOf("llm_usage_daily"))
                RegisteredIncomingRows(SharedDatabase.LLM_USAGE, f.registry, listOf(domain))
            }
            val applier = chatApplier(f)
            val data = """{"id":"session","mode":"general_chat","created_at":"a","updated_at":"a"}"""
            val wrong = s230Change("chat_v2", "chat_v2_sessions", "different", data)
            assertThrows(IllegalArgumentException::class.java) { applier.validate(listOf(wrong)) }
            assertEquals(0L, count(f.db(SharedDatabase.CHAT_V2)))
        }
    }

    @Test fun actualChatForeignKeyFailureRollsBackApplierAndCanBeRetriedInDependencyOrder() {
        S230MigratedFixture(root, s230HostMigrationSource()).use { f ->
            val db = f.db(SharedDatabase.CHAT_V2)
            val applier = chatApplier(f)
            val message = """{"id":"m","session_id":"missing","role":"user","timestamp":9007199254740993}"""
            val bad = s230Change("chat_v2", "chat_v2_messages", "m", message)
            assertThrows(Exception::class.java) { db.transaction { applier.apply(this, listOf(bad)) } }
            assertEquals(0L, db.query("SELECT COUNT(*) FROM chat_v2_messages") { it.long(0) }.single())
            val session = """{"id":"missing","mode":"general_chat","created_at":"a","updated_at":"a"}"""
            val parent = s230Change("chat_v2", "chat_v2_sessions", "missing", session)
            db.transaction { applier.apply(this, listOf(bad, parent)) }
            assertEquals(9007199254740993L, db.query("SELECT timestamp FROM chat_v2_messages WHERE id='m'") { it.long(0) }.single())
        }
    }

    private fun chatApplier(f: S230MigratedFixture): RegisteredSqlRowApplier = RegisteredSqlRowApplier(
        RegisteredIncomingRows(
            SharedDatabase.CHAT_V2,
            f.registry,
            listOf(
                IncomingDomain(SharedDatabase.CHAT_V2, "chat", listOf("chat_v2_sessions", "chat_v2_messages")),
            ),
        ),
    )
    private fun count(db: SqlDatabase): Long = db.query("SELECT COUNT(*) FROM chat_v2_sessions") { it.long(0) }.single()
    private fun session(updated: String, title: String, integer: String): ByteArray = s230Change(
        "chat_v2",
        "chat_v2_sessions",
        "session",
        """{"id":"session","mode":"general_chat","title":"$title","created_at":"2026-01-01T00:00:00Z",""" +
            """"updated_at":"$updated","future":$integer,"__sync_field_deltas":{"future":{"hlc":"000000000000010-00001","dev":"peer"}}}""",
    )
}

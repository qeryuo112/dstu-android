package com.deepstudent.services.sync.data.s230

import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.platform.database.SharedDatabase
import com.deepstudent.platform.database.SqlDatabase
import com.deepstudent.services.sync.backend.SyncFailure
import com.deepstudent.services.sync.data.changelog.ChangeLogReader
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.CancellationException

class S230MigratedApplyTest {
    private val root = Files.createTempDirectory("s230-migrated-").toFile()

    @After fun cleanup() {
        root.deleteRecursively()
    }

    @Test fun laterPrimaryFailureLeavesEachCommittedOwnerReplaySafeAcrossReopen() {
        S230MigratedFixture(root, s230HostMigrationSource()).use { f ->
            val llm = f.db(SharedDatabase.LLM_USAGE)
            llm.execute(
                "INSERT INTO llm_usage_logs(id,timestamp,provider,model,caller_type) " +
                    "VALUES('local','2026-01-01','p','m','chat_v2')",
            )
            val localId = llm.query("SELECT MAX(id) FROM __change_log WHERE record_id='local'") { it.long(0) }.single()
            f.hook = { owner, _ -> if (owner == SharedDatabase.VFS) error("injected primary failure") }
            assertThrows(IllegalStateException::class.java) { f.candidate.apply("peer", 1, s230Batch()) }
            assertEquals(0L, f.candidate.cursor("peer"))
            assertEquals(0L, scalar(f.db(SharedDatabase.VFS), "SELECT COUNT(*) FROM folders WHERE id='folder'"))
            assertEquals(0L, scalar(llm, "SELECT COALESCE(sync_version,0) FROM __change_log WHERE id=$localId"))
            assertEquals(1L, scalar(f.db(SharedDatabase.MISTAKES), "SELECT COUNT(*) FROM anki_decks WHERE id='deck'"))
            assertEquals(1L, scalar(llm, "SELECT COUNT(*) FROM __android_sync_apply_receipts"))
        }
        // Reopen all four real migration-owned SQLite files rather than reusing in-memory state.
        S230MigratedFixture(root, s230HostMigrationSource()).use { f ->
            f.candidate.apply("peer", 1, s230Batch())
            f.candidate.apply("peer", 1, s230Batch())
            assertEquals(1L, f.candidate.cursor("peer"))
            assertEquals(0L, scalar(f.db(SharedDatabase.MISTAKES), "SELECT local_version FROM anki_decks WHERE id='deck'"))
            assertEquals(
                9007199254740993L,
                scalar(f.db(SharedDatabase.LLM_USAGE), "SELECT prompt_tokens FROM llm_usage_logs WHERE id='usage'"),
            )
            assertEquals(1L, scalar(f.db(SharedDatabase.VFS), "SELECT COUNT(*) FROM folders WHERE id='folder'"))
            assertEquals(1L, scalar(f.db(SharedDatabase.VFS), "SELECT COUNT(*) FROM todo_items WHERE id='task' AND todo_list_id='list'"))
            assertEquals(1L, scalar(f.db(SharedDatabase.MISTAKES), "SELECT COUNT(*) FROM mistakes WHERE id='mistake'"))
            for (owner in listOf(SharedDatabase.CHAT_V2, SharedDatabase.MISTAKES, SharedDatabase.LLM_USAGE)) {
                assertEquals(1L, scalar(f.db(owner), "SELECT COUNT(*) FROM __android_sync_apply_receipts"))
            }
        }
    }

    @Test fun failureWithinOwnerRollsBackItsRowsAndReceiptButNotEarlierOwner() {
        S230MigratedFixture(root, s230HostMigrationSource()).use { f ->
            var called = 0
            val secondDeck = s230Change(
                "mistakes",
                "anki_decks",
                "deck2",
                """{"id":"deck2","name":"Second deck","created_at":"2026-01-01","updated_at":"2026-01-01"}""",
            )
            val batch = s230Batch() + secondDeck
            f.hook = { owner, table ->
                if (owner == SharedDatabase.MISTAKES && table == "anki_decks" && ++called == 2) error("owner failure")
            }
            assertThrows(IllegalStateException::class.java) { f.candidate.apply("peer", 1, batch) }
            assertEquals(0L, f.candidate.cursor("peer"))
            assertEquals(1L, scalar(f.db(SharedDatabase.CHAT_V2), "SELECT COUNT(*) FROM chat_v2_sessions WHERE id='session'"))
            assertEquals(0L, scalar(f.db(SharedDatabase.MISTAKES), "SELECT COUNT(*) FROM anki_decks WHERE id IN ('deck','deck2')"))
            assertEquals(0L, scalar(f.db(SharedDatabase.MISTAKES), "SELECT COUNT(*) FROM __android_sync_apply_receipts"))
            assertEquals(0L, scalar(f.db(SharedDatabase.LLM_USAGE), "SELECT COUNT(*) FROM llm_usage_logs WHERE id='usage'"))
            f.hook = { _, _ -> }
            f.candidate.apply("peer", 1, batch)
            assertEquals(1L, f.candidate.cursor("peer"))
            assertEquals(2L, scalar(f.db(SharedDatabase.MISTAKES), "SELECT COUNT(*) FROM anki_decks WHERE id IN ('deck','deck2')"))
            assertEquals(1L, scalar(f.db(SharedDatabase.CHAT_V2), "SELECT COUNT(*) FROM __android_sync_apply_receipts"))
        }
    }

    @Test fun changedOrOmittedDatabaseAfterPartialCommitCannotAdvanceCursor() {
        S230MigratedFixture(root, s230HostMigrationSource()).use { f ->
            val batch = s230Batch()
            f.hook = { owner, _ -> if (owner == SharedDatabase.VFS) error("stop after owner commits") }
            assertThrows(IllegalStateException::class.java) { f.candidate.apply("peer", 1, batch) }
            f.hook = { _, _ -> }
            val omittedChat = batch.filterNot { s230Row(it).table.startsWith("chat_v2_") }
            assertThrows(IllegalStateException::class.java) { f.candidate.apply("peer", 1, omittedChat) }
            val changed = batch.map {
                if (s230Row(it).table ==
                    "folders"
                ) {
                    it.decodeToString().replace("Folder", "Other").toByteArray()
                } else {
                    it
                }
            }
            assertThrows(IllegalStateException::class.java) { f.candidate.apply("peer", 1, changed) }
            assertEquals(0L, f.candidate.cursor("peer"))
            assertEquals(0L, scalar(f.db(SharedDatabase.VFS), "SELECT COUNT(*) FROM folders WHERE id='folder'"))
            f.candidate.apply("peer", 1, batch)
            assertEquals(1L, f.candidate.cursor("peer"))
        }
    }

    @Test fun realChatForeignKeyFailureRollsBackOwnerAndIdenticalRetryOrdersDependencies() {
        S230MigratedFixture(root, s230HostMigrationSource()).use { f ->
            f.orderChatDependencies = false
            assertThrows(Exception::class.java) { f.candidate.apply("peer", 1, s230Batch()) }
            val chat = f.db(SharedDatabase.CHAT_V2)
            assertEquals(1L, scalar(chat, "PRAGMA foreign_keys"))
            assertEquals(0L, scalar(chat, "SELECT COUNT(*) FROM chat_v2_sessions WHERE id='session'"))
            assertEquals(0L, scalar(chat, "SELECT COUNT(*) FROM chat_v2_messages WHERE id='message'"))
            assertEquals(0L, scalar(chat, "SELECT COUNT(*) FROM __android_sync_apply_receipts"))
            assertEquals(0L, f.candidate.cursor("peer"))
            f.orderChatDependencies = true
            f.candidate.apply("peer", 1, s230Batch())
            assertEquals(1L, scalar(chat, "SELECT COUNT(*) FROM chat_v2_session_tags WHERE session_id='session' AND tag='tag'"))
            assertEquals(1L, scalar(chat, "SELECT COUNT(*) FROM chat_v2_blocks WHERE id='block' AND message_id='message'"))
            assertTrue(chat.query("PRAGMA foreign_key_check") { it.text(0) }.isEmpty())
            assertEquals(9007199254740993L, scalar(chat, "SELECT timestamp FROM chat_v2_messages WHERE id='message'"))
        }
    }

    @Test fun exactIntegersNullAndFutureFieldsSurviveVaultAndRealChangeLogReadback() {
        S230MigratedFixture(root, s230HostMigrationSource()).use { f ->
            f.candidate.apply("peer", 1, s230Batch())
            val chat = f.db(SharedDatabase.CHAT_V2)
            val vault = chat.query(
                "SELECT fields_json FROM __sync_unknown_fields WHERE table_name='chat_v2_sessions' AND record_id='session'",
            ) {
                it.text(0)
            }.single()
            val stored = JsonValue.parseObject(vault)
            assertEquals(JsonValue.integer(9007199254740993L), stored["future"])
            assertEquals(JsonValue.Null, stored["nothing"])
            // A later local edit gets a real trigger-created pending log. The existing reader must
            // rehydrate the unknown values; this is not a test-side serialization round trip.
            chat.execute("UPDATE chat_v2_sessions SET title='local edit' WHERE id='session'")
            val keys = f.registry.rowSyncKeys(SharedDatabase.CHAT_V2)
            val change = ChangeLogReader(chat, "chat_v2", { keys[it] }, rowSyncOnly = true).readAll()
                .map { JsonValue.parseObject(it.decodeToString()) }
                .single { (it["table_name"] as JsonValue.Text).value == "chat_v2_sessions" }
            val data = change["data"] as JsonValue.Object
            assertEquals(JsonValue.integer(9007199254740993L), data["future"])
            assertEquals(JsonValue.Null, data["nothing"])
            assertEquals(JsonValue.text("local edit"), data["title"])
            val llm = f.db(SharedDatabase.LLM_USAGE)
            assertEquals(9007199254740993L, scalar(llm, "SELECT total_tokens FROM llm_usage_logs WHERE id='usage'"))
            assertEquals(0L, scalar(llm, "SELECT COUNT(*) FROM __change_log WHERE record_id='usage' AND COALESCE(sync_version,0)=0"))
        }
    }

    @Test fun cancellationInsideOwnerRollsBackAndRetryKeepsEarlierCommit() {
        S230MigratedFixture(root, s230HostMigrationSource()).use { f ->
            f.hook = { owner, _ -> if (owner == SharedDatabase.MISTAKES) throw CancellationException("injected") }
            assertThrows(CancellationException::class.java) { f.candidate.apply("peer", 1, s230Batch()) }
            assertEquals(0L, f.candidate.cursor("peer"))
            assertEquals(0L, scalar(f.db(SharedDatabase.MISTAKES), "SELECT COUNT(*) FROM anki_decks WHERE id='deck'"))
            assertEquals(1L, scalar(f.db(SharedDatabase.CHAT_V2), "SELECT COUNT(*) FROM __android_sync_apply_receipts"))
            f.hook = { _, _ -> }
            f.candidate.apply("peer", 1, s230Batch())
            assertEquals(1L, f.candidate.cursor("peer"))
        }
    }

    @Test fun invalidTableAndSequenceGapFailBeforeOwnerWrites() {
        S230MigratedFixture(root, s230HostMigrationSource()).use { f ->
            val bad = s230Change("llm_usage", "llm_usage_daily", "x", "{}")
            assertThrows(SyncFailure::class.java) { f.candidate.apply("peer", 1, s230Batch() + bad) }
            assertThrows(IllegalArgumentException::class.java) { f.candidate.apply("peer", 2, s230Batch()) }
            assertEquals(0L, f.candidate.cursor("peer"))
            assertEquals(0L, scalar(f.db(SharedDatabase.CHAT_V2), "SELECT COUNT(*) FROM chat_v2_sessions WHERE id='session'"))
            assertEquals(0L, scalar(f.db(SharedDatabase.MISTAKES), "SELECT COUNT(*) FROM anki_decks WHERE id='deck'"))
        }
    }

    @Test fun sequenceAndReceiptKeepAdjacentIntegersBeyondJavascriptSafeRange() {
        S230MigratedFixture(root, s230HostMigrationSource()).use { f ->
            f.primary.setCursor("peer", 9007199254740992L)
            f.candidate.apply("peer", 9007199254740993L, s230Batch())
            assertEquals(9007199254740993L, f.candidate.cursor("peer"))
            assertEquals(9007199254740993L, scalar(f.db(SharedDatabase.CHAT_V2), "SELECT seq FROM __android_sync_apply_receipts"))
            assertNotNull(f.db(SharedDatabase.LLM_USAGE).migrationReport)
            assertFalse(f.registry.rowSyncKeys(SharedDatabase.LLM_USAGE).containsKey("llm_usage_daily"))
        }
    }

    private fun scalar(db: SqlDatabase, sql: String): Long = db.query(sql) { it.long(0) }.single()
}

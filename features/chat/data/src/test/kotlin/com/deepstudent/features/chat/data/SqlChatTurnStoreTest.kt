package com.deepstudent.features.chat.data

import com.deepstudent.core.common.id.TaskId
import com.deepstudent.features.chat.api.ChatTurnContract
import com.deepstudent.features.chat.api.ChatTurnIds
import com.deepstudent.features.chat.api.ChatTurnRequest
import com.deepstudent.features.chat.api.ChatTurnUsage
import com.deepstudent.features.chat.api.ChatTurnUsageMetadata
import com.deepstudent.platform.database.DatabaseFactory
import com.deepstudent.platform.database.MigrationSource
import com.deepstudent.platform.database.SharedDatabase
import com.deepstudent.platform.database.SqlDatabase
import com.deepstudent.services.runtime.api.RuntimeState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.Instant

class SqlChatTurnStoreTest {
    private val root = Files.createTempDirectory("s303-chat-turn-").toFile()
    private val project = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .first { File(it, "config/schema/final-lock.json").isFile }
    private val source = MigrationSource { project.resolve(it).readBytes() }
    private val chatFile = File(root, "chat_v2.db")
    private val usageFile = File(root, "llm_usage.db")
    private val start = Instant.parse("2026-11-26T00:00:00Z")

    @After fun cleanup() {
        root.deleteRecursively()
    }

    private fun openChat(): SqlDatabase = DatabaseFactory.openShared(chatFile, SharedDatabase.CHAT_V2, source)
    private fun openUsage(): SqlDatabase = DatabaseFactory.openShared(usageFile, SharedDatabase.LLM_USAGE, source)
    private fun request(task: String = "task_s303", generation: Long = 1): ChatTurnRequest {
        val id = TaskId.parse(task)
        return ChatTurnRequest(id, generation, ChatTurnContract.sessionId(id), "https://example.test/v1", "问中文👩‍💻")
    }

    @Test fun beginAppendAndCompletePersistStableRowsAndTerminalFact() = runBlocking {
        openChat().use { db ->
            val store = SqlChatTurnStore(db)
            val request = request()
            val ids = ChatTurnIds.of(request.taskId, 1)
            store.begin(request, start)
            store.append(request, "partial 中文", start.plusMillis(100), start.plusMillis(100))
            assertEquals("running", db.scalar("SELECT status FROM chat_v2_blocks WHERE id='${ids.blockId}'"))
            assertEquals("partial 中文", db.scalar("SELECT content FROM chat_v2_blocks WHERE id='${ids.blockId}'"))
            assertEquals(
                start.toString().removeSuffix("Z") + "+00:00",
                db.scalar("SELECT created_at FROM chat_v2_sessions WHERE id='${request.sessionId}'"),
            )
            store.complete(request, "final 中文👩‍💻", null, start.plusSeconds(1))
            assertEquals("success", db.scalar("SELECT status FROM chat_v2_blocks WHERE id='${ids.blockId}'"))
            assertEquals("final 中文👩‍💻", db.scalar("SELECT content FROM chat_v2_blocks WHERE id='${ids.blockId}'"))
            assertEquals("success", db.scalar("SELECT state FROM __android_chat_turn_facts WHERE task_id='task_s303'"))
            assertEquals(request.sessionId, store.find(request.taskId, 1)!!.receipt.let { request.sessionId })
            assertEquals(RuntimeState.COMPLETED, store.find(request.taskId, 1)!!.state)
            assertFalse(
                db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='chat_v2_completion_outbox'") {
                    it.text(0)
                }.isNotEmpty(),
            )
        }
        openChat().use { db ->
            val store = SqlChatTurnStore(db)
            val request = request()
            assertEquals("final 中文👩‍💻", store.loadInFlight(request.taskId, request.sessionId)!!.text)
            assertEquals("success", store.loadInFlight(request.taskId, request.sessionId)!!.blockStatus)
            assertEquals(RuntimeState.COMPLETED, store.find(request.taskId, 1)!!.state)
        }
    }

    @Test fun cancellationWritesErrorBlockButCancelledBusinessFactAndPreservesPartial() = runBlocking {
        openChat().use { db ->
            val store = SqlChatTurnStore(db)
            val request = request()
            val ids = ChatTurnIds.of(request.taskId, 1)
            store.begin(request, start)
            store.append(request, "kept partial", start.plusMillis(50), start.plusMillis(50))
            store.cancel(request, "kept partial", null, start.plusSeconds(1))
            assertEquals("error", db.scalar("SELECT status FROM chat_v2_blocks WHERE id='${ids.blockId}'"))
            assertEquals("kept partial", db.scalar("SELECT content FROM chat_v2_blocks WHERE id='${ids.blockId}'"))
            assertEquals("cancelled", db.scalar("SELECT state FROM __android_chat_turn_facts WHERE task_id='task_s303'"))
            assertEquals(RuntimeState.CANCELLED, store.find(request.taskId, 1)!!.state)
        }
    }

    @Test fun failedFinalAndOutboxRollBackTogetherWhenFactWriteFails() = runBlocking {
        openChat().use { db ->
            val store = SqlChatTurnStore(db)
            val request = request(task = "task_rollback")
            val ids = ChatTurnIds.of(request.taskId, 1)
            store.begin(request, start)
            store.append(request, "before", start.plusMillis(10), start.plusMillis(10))
            db.execute("CREATE TRIGGER fail_turn_fact BEFORE INSERT ON __android_chat_turn_facts BEGIN SELECT RAISE(ABORT,'fixture'); END")
            assertThrows(Exception::class.java) {
                runBlocking { store.complete(request, "after", null, start.plusSeconds(1)) }
            }
            assertEquals("before", db.scalar("SELECT content FROM chat_v2_blocks WHERE id='${ids.blockId}'"))
            assertEquals("running", db.scalar("SELECT status FROM chat_v2_blocks WHERE id='${ids.blockId}'"))
            assertEquals(0L, db.scalar("SELECT count(*) FROM __android_chat_turn_facts").toLong())
        }
    }

    @Test fun crashAfterPartialCommitWithoutCheckpointReadsAndSettlesSharedRowsIdempotently() = runBlocking {
        val request = request(task = "task_crash", generation = 7)
        openChat().use { db ->
            val store = SqlChatTurnStore(db)
            store.begin(request, start)
            store.append(request, "durable partial", start.plusMillis(40), start.plusMillis(40))
            // Simulate death here: no runtime checkpoint exists at all.
        }
        openChat().use { db ->
            val store = SqlChatTurnStore(db)
            val before = assertNotNull(store.loadInFlight(request.taskId, 7, request.sessionId)).let {
                store.loadInFlight(request.taskId, 7, request.sessionId)!!
            }
            assertEquals("durable partial", before.text)
            assertEquals("running", before.blockStatus)
            assertEquals(RuntimeState.INTERRUPTED, store.settleInterrupted(request.taskId, 7, request.sessionId, start.plusSeconds(2)))
            val after = store.loadInFlight(request.taskId, 7, request.sessionId)!!
            assertEquals("durable partial", after.text)
            assertEquals("error", after.blockStatus)
            assertNull(store.find(request.taskId, 7))
            assertNull(store.settleInterrupted(request.taskId, 7, request.sessionId, start.plusSeconds(3)))
            assertEquals(
                "durable partial",
                db.scalar("SELECT content FROM chat_v2_blocks WHERE id='${ChatTurnIds.of(request.taskId, 7).blockId}'"),
            )
        }
    }

    @Test fun duplicateFinalAndConsumerAcksAreIdempotentAndIndependent() = runBlocking {
        openChat().use { db ->
            val store = SqlChatTurnStore(db)
            val request = request(task = "task_ack")
            store.begin(request, start)
            store.complete(request, "answer", null, start.plusSeconds(1))
            store.complete(request, "answer", null, start.plusSeconds(1))
            assertEquals(1L, db.scalar("SELECT count(*) FROM __android_chat_turn_facts").toLong())
            assertEquals(1, store.pending("runtime.recovery", 10).size)
            assertEquals(1, store.pending("usage", 10).size)
            store.acknowledge("runtime.recovery", store.find(request.taskId, 1)!!.factId)
            store.acknowledge("runtime.recovery", store.find(request.taskId, 1)!!.factId)
            assertTrue(store.pending("runtime.recovery", 10).isEmpty())
            assertEquals(1, store.pending("usage", 10).size)
            store.acknowledge("usage", store.find(request.taskId, 1)!!.factId)
            assertTrue(store.pending("usage", 10).isEmpty())
        }
    }

    @Test fun usageReplayIsLazyIdempotentAndPreservesProviderNulls() = runBlocking {
        var usageOpened = 0
        openChat().use { db ->
            val store = SqlChatTurnStore(db)
            val request = request(task = "task_usage")
            store.begin(request, start)
            val usage = ChatTurnUsage(
                ChatTurnUsageMetadata("openai", "gpt-test", "config-a", "openai_chat_completions"),
                promptTokens = 11,
                completionTokens = 7,
                totalTokens = 18,
                reasoningTokens = 3,
                cachedTokens = 2,
            )
            store.complete(request, "answer", usage, start.plusSeconds(1))
            openUsage().use { usageDb ->
                val reconciler = SqlChatTurnUsageReconciler({ db }, {
                    usageOpened++
                    usageDb
                })
                assertEquals(0, usageOpened)
                assertTrue(reconciler.reconcile(request.taskId, 1))
                assertTrue(reconciler.reconcile(request.taskId, 1))
                assertEquals(1L, usageDb.query("SELECT count(*) FROM llm_usage_logs") { it.long(0) }.single())
                val row: List<Any?> = usageDb.query(
                    "SELECT caller_type,session_id,variant_id,run_id,total_tokens,reasoning_tokens,cached_tokens,cache_write_tokens,token_source,status FROM llm_usage_logs",
                ) {
                    listOf<Any?>(
                        it.text(0), it.text(1), it.text(2), it.text(3), it.long(4),
                        if (it.isNull(5)) null else it.long(5), if (it.isNull(6)) null else it.long(6),
                        if (it.isNull(7)) null else it.long(7), it.text(8), it.text(9),
                    )
                }.single()
                assertEquals("chat_v2", row[0])
                assertEquals(request.sessionId, row[1])
                assertEquals(ChatTurnIds.of(request.taskId, 1).assistantId, row[2])
                assertEquals(request.taskId.value, row[3])
                assertEquals(18L, row[4])
                assertEquals(3L, row[5])
                assertEquals(2L, row[6])
                assertNull(row[7])
                assertEquals("api", row[8])
                assertEquals("success", row[9])
                assertEquals(2, usageOpened)
            }
        }
        openChat().use { db ->
            val store = SqlChatTurnStore(db)
            assertTrue(store.pending("usage", 10).isEmpty())
        }
    }

    @Test fun providerPartialUsageNeverInventsMissingTotalOrZero() = runBlocking {
        openChat().use { db ->
            val store = SqlChatTurnStore(db)
            val request = request(task = "task_partial_usage")
            store.begin(request, start)
            store.complete(
                request,
                "answer",
                ChatTurnUsage(
                    ChatTurnUsageMetadata("p", "m", "c", "a"),
                    promptTokens = 11,
                    cachedTokens = null,
                ),
                start.plusSeconds(1),
            )
            openUsage().use { usageDb ->
                val reconciler = SqlChatTurnUsageReconciler({ db }, { usageDb })
                assertEquals(false, reconciler.reconcile(request.taskId, 1))
                assertEquals(0L, usageDb.query("SELECT count(*) FROM llm_usage_logs") { it.long(0) }.single())
                val pending = reconciler.pending().single().counts
                assertEquals(11L, pending.promptTokens)
                assertNull(pending.completionTokens)
                assertNull(pending.totalTokens)
                assertNull(pending.cachedTokens)
            }
        }
    }

    @Test fun usageTransientFailureLeavesBothConsumersPendingUntilReplay() = runBlocking {
        openChat().use { db ->
            val store = SqlChatTurnStore(db)
            val request = request(task = "task_usage_fail")
            store.begin(request, start)
            store.complete(
                request,
                "answer",
                ChatTurnUsage(
                    ChatTurnUsageMetadata("p", "m", "c", "a"),
                    promptTokens = 1,
                    completionTokens = 2,
                ),
                start.plusSeconds(1),
            )
            openUsage().use { usageDb ->
                val reconciler = SqlChatTurnUsageReconciler({ db }, { error("usage unavailable") })
                assertThrows(Exception::class.java) { runBlocking { reconciler.reconcile(request.taskId, 1) } }
                assertEquals(1, store.pending("usage", 10).size)
                assertEquals(1, store.pending("runtime.recovery", 10).size)
                val replay = SqlChatTurnUsageReconciler({ db }, { usageDb })
                assertTrue(replay.reconcile(request.taskId, 1))
                assertEquals(1L, usageDb.query("SELECT count(*) FROM llm_usage_logs") { it.long(0) }.single())
                assertEquals(1, store.pending("runtime.recovery", 10).size)
                assertTrue(store.pending("usage", 10).isEmpty())
            }
        }
    }

    @Test fun providerWithoutUsageCreatesNoLlmUsageRow() = runBlocking {
        openChat().use { db ->
            val store = SqlChatTurnStore(db)
            val request = request(task = "task_no_usage")
            store.begin(request, start)
            store.complete(request, "answer", null, start.plusSeconds(1))
            openUsage().use { usageDb ->
                val reconciler = SqlChatTurnUsageReconciler({ db }, { usageDb })
                assertFalse(reconciler.reconcile(request.taskId, 1))
                assertEquals(0L, usageDb.query("SELECT count(*) FROM llm_usage_logs") { it.long(0) }.single())
            }
        }
    }

    private fun SqlDatabase.scalar(sql: String): String = query(sql) { if (it.isNull(0)) "NULL" else it.text(0) }.single()
    private fun SqlDatabase.scalarLong(sql: String): Long = query(sql) { it.long(0) }.single()
}

private fun SqlDatabase.scalar(sql: String): String = query(sql) { if (it.isNull(0)) "NULL" else it.text(0) }.single()
private fun SqlDatabase.scalarLong(sql: String): Long = query(sql) { it.long(0) }.single()

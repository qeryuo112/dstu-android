package com.deepstudent.features.chat.data

import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.core.common.logging.LogRecord
import com.deepstudent.core.common.logging.Logger
import com.deepstudent.features.chat.api.BlockDetails
import com.deepstudent.features.chat.api.BlockField
import com.deepstudent.features.chat.api.BlockTiming
import com.deepstudent.features.chat.api.ChatBlock
import com.deepstudent.features.chat.api.ChatConversation
import com.deepstudent.features.chat.api.ChatMessage
import com.deepstudent.features.chat.api.ChatSession
import com.deepstudent.features.chat.api.MessageField
import com.deepstudent.features.chat.api.MessageLinks
import com.deepstudent.features.chat.api.MessagePayload
import com.deepstudent.features.chat.api.SessionDetails
import com.deepstudent.platform.database.DatabaseFactory
import com.deepstudent.platform.database.MigrationSource
import com.deepstudent.platform.database.SharedDatabase
import com.deepstudent.platform.database.SqlDatabase
import com.deepstudent.platform.database.SqlOperations
import com.deepstudent.platform.database.SqlReadOperations
import com.deepstudent.platform.database.SqlRow
import com.deepstudent.platform.database.SqlValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real migrated SQLite on both host and Android. IDs/content follow the frozen desktop
 * host/plugins/chat/tests/message-persistence.test.ts `test_save_results_basic` output shape.
 * Additional opaque JSON and governance columns deliberately exercise the full physical schema. */
class ChatRepositorySqliteContract(private val root: File, private val source: MigrationSource) {
    private val file = root.resolve("chat_v2.db")
    private fun open() = DatabaseFactory.openShared(file, SharedDatabase.CHAT_V2, source)

    fun restartAndProjection() = runBlocking {
        open().use { db ->
            val repo = SqlChatRepository(db)
            repo.save(sample())
            db.execute(
                "UPDATE chat_v2_sessions SET workspace_id='workspace',device_id='device',local_version=9223372036854775807,deleted_at='tombstone',tags_hash='tags',last_compaction_id='compaction' WHERE id='sess_persist'",
            )
            db.execute(
                "UPDATE chat_v2_messages SET device_id='msg-device',local_version=22,updated_at='msg-clock',deleted_at='msg-deleted' WHERE id='msg_assistant'",
            )
            db.execute(
                "UPDATE chat_v2_blocks SET device_id='block-device',local_version=33,updated_at='block-clock',deleted_at='block-deleted',compacted_at=44,llm_content='replay',tool_call_id='call',round_text='round' WHERE id='blk_1'",
            )
        }
        open().use { db ->
            assertEquals(0, db.migrationReport?.appliedCount)
            var calls = 0
            val repo = SqlChatRepository(database = {
                calls++
                db
            })
            assertEquals(0, calls)
            val loaded = repo.load("sess_persist")!!
            assertEquals(1, calls)
            val expected = sample()
            assertEquals(expected.session, loaded.session.copy(storage = expected.session.storage))
            expected.messages.forEach { original ->
                val actual = loaded.messages.single { it.id == original.id }
                assertEquals(original, actual.copy(storage = original.storage))
            }
            expected.blocks.forEach { original ->
                val actual = loaded.blocks.single { it.id == original.id }
                assertEquals(original, actual.copy(storage = original.storage))
            }
            assertEquals(listOf("msg_user", "msg_assistant"), loaded.messages.map { it.id })
            assertEquals(listOf("blk_user_1", "blk_2", "blk_1", "blk_0"), loaded.blocks.map { it.id })
            assertEquals(Long.MAX_VALUE, loaded.session.storage.localVersion)
            assertEquals("workspace", loaded.session.storage.workspaceId)
            assertEquals("device", loaded.session.storage.deviceId)
            assertEquals("tags", loaded.session.storage.tagsHash)
            assertEquals("compaction", loaded.session.storage.lastCompactionId)
            assertEquals("tombstone", loaded.session.storage.deletedAt)
            val assistant = loaded.messages[1]
            assertEquals("msg-device", assistant.storage.deviceId)
            assertEquals(22L, assistant.storage.localVersion)
            assertEquals("msg-clock", assistant.storage.updatedAt)
            assertEquals("msg-deleted", assistant.storage.deletedAt)
            assertEquals(listOf("variant_a", "variant_b"), assistant.variantItems()!!.map { it.id })
            assertEquals(Long.MAX_VALUE, assistant.variantItems()!![0].createdAt)
            val variant = assistant.variantItems()!![0].value
            assertEquals(JsonValue.Null, (variant["future"] as JsonValue.Object)["nullValue"])
            assertEquals("9223372036854775807", ((variant["future"] as JsonValue.Object)["integer"] as JsonValue.Number).literal)
            assertEquals(JsonValue.Null, assistant.payload.attachments)
            assertNull(assistant.payload.sharedContext)
            val block = loaded.blocks.single { it.id == "blk_1" }
            assertEquals("variant_b", block.details.variantId)
            assertEquals(JsonValue.Null, block.details.toolInput)
            assertNull(block.details.toolOutput)
            assertEquals("replay", block.storage.llmContent)
            assertEquals("call", block.storage.toolCallId)
            assertEquals("round", block.storage.roundText)
            assertEquals(44L, block.storage.compactedAt)
            assertEquals("block-deleted", block.storage.deletedAt)
            assertEquals("block-device", block.storage.deviceId)
            assertEquals("block-clock", block.storage.updatedAt)
            assertEquals(33L, block.storage.localVersion)
            val count = changeCount(db)
            repo.save(loaded)
            assertEquals("identical save must not emit sync changes", count, changeCount(db))
            assertEquals("replay", scalar(db, "SELECT llm_content FROM chat_v2_blocks WHERE id='blk_1'"))
            assertEquals("null", scalar(db, "SELECT tool_input_json FROM chat_v2_blocks WHERE id='blk_1'"))
            assertEquals("SQL NULL", scalar(db, "SELECT COALESCE(tool_output_json,'SQL NULL') FROM chat_v2_blocks WHERE id='blk_1'"))
        }
    }

    fun partialUpdateAndReplay() = runBlocking {
        open().use { db ->
            val repo = SqlChatRepository(db)
            val original = sample()
            repo.save(original)
            db.execute("UPDATE chat_v2_blocks SET llm_content='replay',tool_call_id='call',round_text='round' WHERE id='blk_1'")
            val assistant = original.messages[1]
            val block = original.blocks.single { it.id == "blk_1" }
            val partial = ChatConversation(
                original.session.copy(details = original.session.details.copy(metadata = null, description = null)),
                listOf(ChatMessage(assistant.id, assistant.sessionId, assistant.role, assistant.blockIds, assistant.timestamp)),
                listOf(block.copy(details = BlockDetails(content = block.details.content), timing = BlockTiming())),
            )
            repo.save(partial)
            val reloaded = repo.load("sess_persist")!!
            assertNotNull(reloaded.session.details.metadata)
            assertNotNull(reloaded.messages[1].payload.variants)
            assertEquals("variant_b", reloaded.blocks.single { it.id == "blk_1" }.details.variantId)
            assertEquals("replay", reloaded.blocks.single { it.id == "blk_1" }.storage.llmContent)
            assertEquals(4, reloaded.blocks.size)
            repo.save(
                partial.copy(
                    messages = listOf(partial.messages.single().copy(payload = MessagePayload(clear = setOf(MessageField.VARIANTS)))),
                    blocks = listOf(
                        partial.blocks.single().copy(
                            details = BlockDetails(
                                content = "edited",
                                toolOutput = JsonValue.Null,
                                clear = setOf(BlockField.TOOL_INPUT),
                            ),
                        ),
                    ),
                ),
            )
            val changed = repo.load("sess_persist")!!
            assertNull(changed.messages[1].payload.variants)
            val edited = changed.blocks.single { it.id == "blk_1" }
            assertEquals("edited", edited.details.content)
            assertNull(edited.storage.llmContent)
            assertEquals("call", edited.storage.toolCallId)
            assertEquals("round", edited.storage.roundText)
            assertNull(edited.details.toolInput)
            assertEquals(JsonValue.Null, edited.details.toolOutput)
        }
    }

    fun rollbackOnFailedRow() = runBlocking {
        open().use { db ->
            val repo = SqlChatRepository(db)
            val initial = sample()
            repo.save(initial)
            val before = changeCount(db)
            val broken = initial.copy(
                session = initial.session.copy(details = initial.session.details.copy(title = "must rollback")),
                messages = listOf(initial.messages[0].copy(role = "not-a-role")),
            )
            try {
                repo.save(broken)
                fail("invalid SQLite role must fail")
            } catch (_: Exception) { }
            assertEquals(before, changeCount(db))
            assertEquals(initial.session.details.title, repo.load("sess_persist")!!.session.details.title)
            assertEquals("user", repo.load("sess_persist")!!.messages[0].role)
        }
    }

    fun cancellationBeforeCommitRollsBack() {
        open().use { db ->
            val job = Job()
            val events = mutableListOf<LogRecord>()
            val interrupted = object : SqlDatabase by db {
                override fun <T> transaction(block: SqlOperations.() -> T): T = db.transaction {
                    val owner = this
                    val decorated = object : SqlOperations by owner {
                        override fun execute(sql: String, args: List<SqlValue>) {
                            owner.execute(sql, args)
                            if (sql.startsWith("INSERT INTO chat_v2_blocks")) job.cancel()
                        }
                    }
                    block(decorated)
                }
            }
            val repo = SqlChatRepository(
                interrupted,
                object : Logger {
                    override fun log(record: LogRecord) {
                        events.add(record)
                    }
                },
            )
            try {
                runBlocking(job) { repo.save(sample()) }
                fail("cancelled save must fail")
            } catch (_: CancellationException) { }
            assertTrue(db.query("SELECT id FROM chat_v2_sessions") { it.text(0) }.isEmpty())
            assertTrue(events.any { it.event == "chat.repository.save_cancelled" })
            assertFalse(events.any { it.event == "chat.repository.save_failed" })
        }
    }

    fun readSnapshotIsConsistent() = runBlocking {
        open().use { db ->
            val base = SqlChatRepository(db)
            base.save(sample())
            val updated = CountDownLatch(1)
            var writerFailure: Throwable? = null
            val decorated = object : SqlDatabase by db {
                override fun <T> readSnapshot(block: SqlReadOperations.() -> T): T = db.readSnapshot {
                    val reader = this
                    val scoped = object : SqlReadOperations by reader {
                        override fun <R> query(sql: String, args: List<SqlValue>, map: (SqlRow) -> R): List<R> {
                            val result = reader.query(sql, args, map)
                            if (sql.contains("FROM chat_v2_sessions WHERE id=")) {
                                Thread {
                                    try {
                                        db.execute("UPDATE chat_v2_blocks SET content='after snapshot' WHERE id='blk_1'")
                                    } catch (
                                        failure: Throwable,
                                    ) {
                                        writerFailure = failure
                                    } finally {
                                        updated.countDown()
                                    }
                                }.start()
                                check(updated.await(10, TimeUnit.SECONDS))
                                writerFailure?.let { throw it }
                            }
                            return result
                        }
                    }
                    block(scoped)
                }
            }
            assertEquals("块内容 1", SqlChatRepository(decorated).load("sess_persist")!!.blocks.single { it.id == "blk_1" }.details.content)
            assertEquals("after snapshot", base.load("sess_persist")!!.blocks.single { it.id == "blk_1" }.details.content)
        }
    }

    private fun scalar(db: SqlDatabase, sql: String) = db.query(sql) { it.text(0) }.single()
    private fun changeCount(db: SqlDatabase) = db.query("SELECT COUNT(*) FROM __change_log") { it.long(0) }.single()
}

private fun sample(): ChatConversation {
    val future = JsonValue.parse("""{"future":{"nested":[null,{"large":9223372036854775807}]}}""")
    val variants = JsonValue.parse(
        """[{"id":"variant_a","modelId":"model-a","blockIds":["blk_0"],"status":"success","createdAt":9223372036854775807,"future":{"nullValue":null,"integer":9223372036854775807}},{"id":"variant_b","modelId":"model-b","blockIds":["blk_1"],"status":"success","createdAt":2}]""",
    )
    val session = ChatSession(
        "sess_persist",
        "chat",
        "2026-01-01T00:00:00Z",
        "2026-01-01T00:00:01Z",
        details = SessionDetails(
            title = "标题",
            metadata = future,
            description = "描述",
            summaryHash = "summary",
            groupId = "group",
            titleLocked = true,
        ),
    )
    val user = ChatMessage(
        "msg_user",
        session.id,
        "user",
        listOf("blk_user_1"),
        1000,
        links = MessageLinks(persistentStableId = "stable"),
        payload = MessagePayload(sharedContext = future),
    )
    val assistant = ChatMessage(
        "msg_assistant",
        session.id,
        "assistant",
        listOf("blk_0", "blk_1", "blk_2"),
        1000,
        links = MessageLinks(parentId = "parent", supersedes = "superseded", activeVariantId = "variant_a"),
        payload = MessagePayload(metadata = future, attachments = JsonValue.Null, variants = variants),
    )
    val blocks = listOf(
        ChatBlock("blk_user_1", user.id, "content", "success", 0, BlockDetails(content = "用户问题内容")),
        ChatBlock(
            "blk_0",
            assistant.id,
            "thinking",
            "success",
            0,
            details = BlockDetails(content = "块内容 0", variantId = "variant_a"),
            timing = BlockTiming(firstChunkAt = 20),
        ),
        ChatBlock(
            "blk_1",
            assistant.id,
            "mcp_tool",
            "success",
            0,
            details = BlockDetails(
                content = "块内容 1",
                toolName = "tool",
                toolInput = JsonValue.Null,
                citations = future,
                error = "diagnostic",
                variantId = "variant_b",
            ),
            timing = BlockTiming(startedAt = 10, endedAt = 11),
        ),
        ChatBlock(
            "blk_2",
            assistant.id,
            "content",
            "success",
            0,
            details = BlockDetails(content = "块内容 2", toolOutput = future),
            timing = BlockTiming(firstChunkAt = 5),
        ),
    )
    return ChatConversation(session, listOf(user, assistant), blocks)
}

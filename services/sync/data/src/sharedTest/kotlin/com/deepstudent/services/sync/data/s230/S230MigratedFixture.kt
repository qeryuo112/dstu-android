package com.deepstudent.services.sync.data.s230

import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.core.common.logging.Logger
import com.deepstudent.core.common.logging.NoopLogger
import com.deepstudent.platform.database.DatabaseFactory
import com.deepstudent.platform.database.MigrationSource
import com.deepstudent.platform.database.SharedDatabase
import com.deepstudent.platform.database.SqlDatabase
import com.deepstudent.platform.database.SqlOperations
import com.deepstudent.platform.database.SqlValue
import com.deepstudent.services.sync.api.DomainSyncAdapter
import com.deepstudent.services.sync.data.apply.IncomingApplyCallbacks
import com.deepstudent.services.sync.data.apply.IncomingBatchCoordinator
import com.deepstudent.services.sync.data.apply.SqlIncomingOwner
import com.deepstudent.services.sync.data.capture.SyncCaptureRegistry
import java.io.File

/**
 * Test-only owners: deliberately narrow field mappings and deliberately non-idempotent updates.
 * They exercise coordination against real migrated tables, not production conflict resolution.
 */
internal class S230MigratedFixture(private val root: File, val source: MigrationSource, logger: Logger = NoopLogger) : AutoCloseable {
    val registry = SyncCaptureRegistry.load(source)
    val databases: Map<SharedDatabase, SqlDatabase> = SharedDatabase.entries.associateWith {
        DatabaseFactory.openShared(root.resolve("${it.id}.db"), it, source)
    }
    var hook: (SharedDatabase, String) -> Unit = { _, _ -> }
    var orderChatDependencies = true
    val primary = S230Primary(this)
    var beforeConsume: () -> Unit = {}
    val candidate = IncomingBatchCoordinator(
        primary,
        SharedDatabase.VFS,
        setOf("folders", "todo_lists", "todo_items"),
        listOf(
            SqlIncomingOwner(
                SharedDatabase.CHAT_V2,
                { db(SharedDatabase.CHAT_V2) },
                setOf("chat_v2_sessions", "chat_v2_messages", "chat_v2_blocks", "chat_v2_session_tags"),
            ) { applyChat(it) },
            SqlIncomingOwner(SharedDatabase.MISTAKES, { db(SharedDatabase.MISTAKES) }, setOf("anki_decks", "mistakes")) { applyDecks(it) },
            SqlIncomingOwner(SharedDatabase.LLM_USAGE, { db(SharedDatabase.LLM_USAGE) }, setOf("llm_usage_logs")) { applyUsage(it) },
        ),
        registry,
        "fixture-instance",
        callbacks = IncomingApplyCallbacks(validatePrimary = { beforeConsume() }, logger = logger),
    )

    fun db(owner: SharedDatabase): SqlDatabase = databases.getValue(owner)
    override fun close() = databases.values.toList().asReversed().forEach { it.close() }

    private fun SqlOperations.applyChat(rows: List<ByteArray>) {
        val decoded = rows.map(::s230Row)
        val sorted = if (orderChatDependencies) {
            decoded.sortedBy {
                when (it.table) {
                    "chat_v2_sessions" -> 0
                    "chat_v2_messages" -> 1
                    "chat_v2_blocks" -> 2
                    else -> 3
                }
            }
        } else {
            decoded
        }
        for (row in sorted) {
            val d = row.data
            when (row.table) {
                "chat_v2_sessions" -> execute(
                    "INSERT INTO chat_v2_sessions(id,mode,title,created_at,updated_at) VALUES(?,?,?,?,?) " +
                        "ON CONFLICT(id) DO UPDATE SET title=excluded.title",
                    listOf(
                        s230Text(d, "id"),
                        s230Text(d, "mode"),
                        s230Text(d, "title"),
                        s230Text(d, "created_at"),
                        s230Text(d, "updated_at"),
                    ),
                )

                "chat_v2_messages" -> execute(
                    "INSERT INTO chat_v2_messages(id,session_id,role,timestamp) VALUES(?,?,?,?) " +
                        "ON CONFLICT(id) DO UPDATE SET timestamp=excluded.timestamp",
                    listOf(s230Text(d, "id"), s230Text(d, "session_id"), s230Text(d, "role"), s230Integer(d, "timestamp")),
                )

                "chat_v2_blocks" -> execute(
                    "INSERT INTO chat_v2_blocks(id,message_id,block_type,content) VALUES(?,?,?,?) " +
                        "ON CONFLICT(id) DO UPDATE SET content=excluded.content",
                    listOf(s230Text(d, "id"), s230Text(d, "message_id"), s230Text(d, "block_type"), s230Text(d, "content")),
                )

                "chat_v2_session_tags" -> execute(
                    "INSERT INTO chat_v2_session_tags(session_id,tag) VALUES(?,?) ON CONFLICT(session_id,tag) DO NOTHING",
                    listOf(s230Text(d, "session_id"), s230Text(d, "tag")),
                )

                else -> error("unexpected fixture table")
            }
            s230VaultFixtureFields(row)
            hook(SharedDatabase.CHAT_V2, row.table)
        }
    }

    private fun SqlOperations.applyDecks(rows: List<ByteArray>) {
        for (bytes in rows) {
            val row = s230Row(bytes)
            val d = row.data
            when (row.table) {
                "anki_decks" -> execute(
                    // This intentionally increments on a second delivery to expose a missing receipt.
                    "INSERT INTO anki_decks(id,name,created_at,updated_at,local_version) VALUES(?,?,?,?,0) " +
                        "ON CONFLICT(id) DO UPDATE SET local_version=anki_decks.local_version+1",
                    listOf(s230Text(d, "id"), s230Text(d, "name"), s230Text(d, "created_at"), s230Text(d, "updated_at")),
                )

                "mistakes" -> execute(
                    "INSERT INTO mistakes(id,created_at,question_images,analysis_images,user_question," +
                        "ocr_text,tags,mistake_type,status,updated_at) " +
                        "VALUES(?,?,?,?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET user_question=excluded.user_question",
                    listOf(
                        s230Text(d, "id"), s230Text(d, "created_at"), s230Text(d, "question_images"),
                        s230Text(d, "analysis_images"), s230Text(d, "user_question"), s230Text(d, "ocr_text"),
                        s230Text(d, "tags"), s230Text(d, "mistake_type"), s230Text(d, "status"), s230Text(d, "updated_at"),
                    ),
                )

                else -> error("unexpected fixture table")
            }
            s230VaultFixtureFields(row)
            hook(SharedDatabase.MISTAKES, row.table)
        }
    }

    private fun SqlOperations.applyUsage(rows: List<ByteArray>) {
        for (bytes in rows) {
            val row = s230Row(bytes)
            val d = row.data
            // Also intentionally non-idempotent: the transaction receipt, not this SQL, stops replay.
            execute(
                "INSERT INTO llm_usage_logs(id,timestamp,provider,model,caller_type,prompt_tokens,total_tokens) VALUES(?,?,?,?,?,?,?) " +
                    "ON CONFLICT(id) DO UPDATE SET prompt_tokens=llm_usage_logs.prompt_tokens+excluded.prompt_tokens",
                listOf(
                    s230Text(d, "id"),
                    s230Text(d, "timestamp"),
                    s230Text(d, "provider"),
                    s230Text(d, "model"),
                    s230Text(d, "caller_type"),
                    s230Integer(d, "prompt_tokens"),
                    s230Integer(d, "total_tokens"),
                ),
            )
            s230VaultFixtureFields(row)
            hook(SharedDatabase.LLM_USAGE, row.table)
        }
    }
}

/** A minimal primary owner implements the same atomic row+cursor precondition as SqlNotesSyncAdapter. */
internal class S230Primary(private val fixture: S230MigratedFixture) : DomainSyncAdapter {
    private val db get() = fixture.db(SharedDatabase.VFS)
    init {
        db.execute("CREATE TABLE IF NOT EXISTS __android_s230_test_cursor(device TEXT PRIMARY KEY,seq INTEGER NOT NULL)")
    }
    override fun pending(): List<ByteArray> = emptyList()
    override fun markPublished(changeLogIds: List<Long>, version: Long) = Unit
    override fun cursor(device: String): Long = db.query(
        "SELECT seq FROM __android_s230_test_cursor WHERE device=?",
        listOf(SqlValue.Text(device)),
    ) { it.long(0) }.singleOrNull() ?: 0L

    fun setCursor(device: String, seq: Long) = db.execute(
        "INSERT INTO __android_s230_test_cursor(device,seq) VALUES(?,?) ON CONFLICT(device) DO UPDATE SET seq=excluded.seq",
        listOf(SqlValue.Text(device), SqlValue.Integer(seq)),
    )

    override fun apply(device: String, seq: Long, changes: List<ByteArray>) = db.transaction {
        val before = query("SELECT COALESCE(MAX(id),0) FROM __change_log") { it.long(0) }.single()
        for (bytes in changes) {
            val row = s230Row(bytes)
            val d = row.data
            when (row.table) {
                "folders" -> execute(
                    "INSERT INTO folders(id,title,created_at,updated_at) VALUES(?,?,?,?) " +
                        "ON CONFLICT(id) DO UPDATE SET title=excluded.title",
                    listOf(s230Text(d, "id"), s230Text(d, "title"), s230Integer(d, "created_at"), s230Integer(d, "updated_at")),
                )

                "todo_lists" -> execute(
                    "INSERT INTO todo_lists(id,title,created_at,updated_at) VALUES(?,?,?,?) " +
                        "ON CONFLICT(id) DO UPDATE SET title=excluded.title",
                    listOf(s230Text(d, "id"), s230Text(d, "title"), s230Text(d, "created_at"), s230Text(d, "updated_at")),
                )

                "todo_items" -> execute(
                    "INSERT INTO todo_items(id,todo_list_id,title,created_at,updated_at) VALUES(?,?,?,?,?) " +
                        "ON CONFLICT(id) DO UPDATE SET title=excluded.title",
                    listOf(
                        s230Text(d, "id"),
                        s230Text(d, "todo_list_id"),
                        s230Text(d, "title"),
                        s230Text(d, "created_at"),
                        s230Text(d, "updated_at"),
                    ),
                )

                else -> error("unexpected fixture table")
            }
            s230VaultFixtureFields(row)
            fixture.hook(SharedDatabase.VFS, row.table)
        }
        execute(
            "UPDATE __change_log SET sync_version=1 WHERE id>? AND COALESCE(sync_version,0)=0",
            listOf(SqlValue.Integer(before)),
        )
        execute(
            "INSERT INTO __android_s230_test_cursor(device,seq) VALUES(?,?) " +
                "ON CONFLICT(device) DO UPDATE SET seq=excluded.seq",
            listOf(SqlValue.Text(device), SqlValue.Integer(seq)),
        )
    }
}

internal data class S230FixtureRow(val table: String, val id: String, val data: JsonValue.Object)
internal fun s230Row(bytes: ByteArray): S230FixtureRow {
    val value = JsonValue.parseObject(bytes.decodeToString())
    return S230FixtureRow(
        (value["table_name"] as JsonValue.Text).value,
        (value["record_id"] as JsonValue.Text).value,
        value["data"] as JsonValue.Object,
    )
}
internal fun s230Text(value: JsonValue.Object, name: String) = SqlValue.Text((value[name] as JsonValue.Text).value)
internal fun s230Integer(value: JsonValue.Object, name: String) =
    SqlValue.Integer(requireNotNull((value[name] as JsonValue.Number).asLongOrNull()))

/** Just the fixture's known future keys, stored in the desktop-compatible vault for readback. */
private fun SqlOperations.s230VaultFixtureFields(row: S230FixtureRow) {
    val unknown = row.data.asMap().filterKeys { it == "future" || it == "nothing" }
    if (unknown.isEmpty()) return
    execute(
        "CREATE TABLE IF NOT EXISTS __sync_unknown_fields(table_name TEXT NOT NULL,record_id TEXT NOT NULL," +
            "fields_json TEXT NOT NULL,updated_at TEXT NOT NULL DEFAULT (datetime('now')),PRIMARY KEY(table_name,record_id))",
    )
    execute(
        "INSERT INTO __sync_unknown_fields(table_name,record_id,fields_json) VALUES(?,?,?) " +
            "ON CONFLICT(table_name,record_id) DO UPDATE SET fields_json=excluded.fields_json",
        listOf(SqlValue.Text(row.table), SqlValue.Text(row.id), SqlValue.Text(JsonValue.Object.from(unknown).literal())),
    )
}

internal fun s230Change(database: String, table: String, id: String, data: String): ByteArray =
    JsonValue.Object.Builder().put("database_name", database).put("table_name", table).put("record_id", id)
        .put("operation", "Insert").put("changed_at", "2026-01-01T00:00:00Z")
        .put("data", JsonValue.parseObject(data)).build().literal().toByteArray()

internal fun s230Batch(): List<ByteArray> = listOf(
    s230Change("vfs", "folders", "folder", """{"id":"folder","title":"Folder","created_at":1,"updated_at":1}"""),
    s230Change("vfs", "todo_lists", "list", """{"id":"list","title":"List","created_at":"2026-01-01","updated_at":"2026-01-01"}"""),
    s230Change(
        "vfs",
        "todo_items",
        "task",
        """{"id":"task","todo_list_id":"list","title":"Task",
        "created_at":"2026-01-01","updated_at":"2026-01-01"}""",
    ),
    // The real FK requires session before message before block; the fixture owner orders these deliberately.
    s230Change("chat_v2", "chat_v2_blocks", "block", """{"id":"block","message_id":"message","block_type":"content","content":"hello"}"""),
    s230Change(
        "chat_v2",
        "chat_v2_messages",
        "message",
        """{"id":"message","session_id":"session",
        "role":"user","timestamp":9007199254740993}""",
    ),
    s230Change(
        "chat_v2",
        "chat_v2_sessions",
        "session",
        """{"id":"session","mode":"general_chat","title":"Chat",
        "created_at":"2026-01-01","updated_at":"2026-01-01","future":9007199254740993,"nothing":null}""",
    ),
    s230Change("chat_v2", "chat_v2_session_tags", """{"session_id":"session","tag":"tag"}""", """{"session_id":"session","tag":"tag"}"""),
    s230Change(
        "mistakes",
        "anki_decks",
        "deck",
        """{"id":"deck","name":"Fixture deck",
        "created_at":"2026-01-01","updated_at":"2026-01-01"}""",
    ),
    s230Change(
        "mistakes",
        "mistakes",
        "mistake",
        """{"id":"mistake","created_at":"2026-01-01",
        "question_images":"[]","analysis_images":"[]","user_question":"question","ocr_text":"","tags":"[]",
        "mistake_type":"other","status":"active","updated_at":"2026-01-01"}""",
    ),
    s230Change(
        "llm_usage",
        "llm_usage_logs",
        "usage",
        """{"id":"usage","timestamp":"2026-01-01T00:00:00Z",
        "provider":"p","model":"m","caller_type":"chat_v2","prompt_tokens":9007199254740993,
        "total_tokens":9007199254740993}""",
    ),
)

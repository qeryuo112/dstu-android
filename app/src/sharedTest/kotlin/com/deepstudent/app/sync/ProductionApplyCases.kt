package com.deepstudent.app.sync

import com.deepstudent.app.storage.MainSyncDatabases
import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.core.common.logging.Logger
import com.deepstudent.engines.zstd.ZstdEngine
import com.deepstudent.platform.database.DatabaseFactory
import com.deepstudent.platform.database.MigrationSource
import com.deepstudent.platform.database.SharedDatabase
import com.deepstudent.platform.database.SqlDatabase
import com.deepstudent.platform.database.SqlValue
import com.deepstudent.platform.security.SecurityPorts
import com.deepstudent.services.sync.api.SyncConfiguration
import com.deepstudent.services.sync.api.SyncError
import com.deepstudent.services.sync.api.SyncResult
import com.deepstudent.services.sync.api.SyncTransfer
import com.deepstudent.services.sync.backend.ObjectProvider
import com.deepstudent.services.sync.backend.PairingService
import com.deepstudent.services.sync.backend.ProviderFactory
import com.deepstudent.services.sync.backend.RepositoryEncryption
import com.deepstudent.services.sync.backend.SyncBatchCodec
import com.deepstudent.services.sync.backend.SyncWireCodec
import com.deepstudent.services.sync.data.LocalSyncStore
import com.deepstudent.services.sync.data.capture.CaptureLog
import kotlinx.coroutines.CoroutineScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import java.io.File

internal class ProductionApplyCases(
    private val source: MigrationSource,
    private val directory: File,
    private val logger: Logger,
    private val securityFactory: (File) -> SecurityPorts,
) {
    suspend fun roundtrip(scope: CoroutineScope) {
        val remote = ApplyFixtureProvider()
        withInstallation("source", remote, scope) { db, transfer, _ ->
            seedDomainRows(db)
            assertEquals("source send", SyncResult.Success(Unit), transfer.synchronize())
            assertEquals(remote.changeInventory(), 1, remote.changeCount())
        }
        withInstallation("target", remote, scope) { db, transfer, _ ->
            assertEquals("target receive", SyncResult.Success(Unit), transfer.synchronize())
            verifyDomainRows(db)
            val counts = db.mapValues { (_, owner) -> owner.changeLogCount() }
            assertEquals("target replay", SyncResult.Success(Unit), transfer.synchronize())
            verifyDomainRows(db)
            assertEquals(counts, db.mapValues { (_, owner) -> owner.changeLogCount() })
            assertEquals(remote.changeInventory(), 1, remote.changeCount())
        }
    }

    suspend fun replay(scope: CoroutineScope) {
        val remote = ApplyFixtureProvider()
        val sourceId = withInstallation("send", remote, scope) { db, transfer, id ->
            seedDomainRows(db)
            assertEquals(SyncResult.Success(Unit), transfer.synchronize())
            id
        }
        withInstallation("receive", remote, scope) { db, transfer, _ ->
            val vfs = db.getValue(SharedDatabase.VFS)
            vfs.execute(
                "CREATE TEMP TRIGGER s230_fault BEFORE INSERT ON main.todo_items WHEN NEW.id='s230-todo' " +
                    "BEGIN SELECT RAISE(ABORT,'synthetic apply fault'); END",
            )
            assertEquals(SyncResult.Failure(SyncError.STORAGE), transfer.synchronize())
            assertEquals(0L, vfs.cursor(sourceId))
            assertEquals(0L, vfs.countRows("todo_items", "id", "s230-todo"))
            val chat = db.getValue(SharedDatabase.CHAT_V2)
            assertEquals(1L, chat.countRows("chat_v2_messages", "id", "s230-message"))
            val chatLog = chat.changeLogCount()
            vfs.execute("DROP TRIGGER temp.s230_fault")
            assertEquals(SyncResult.Success(Unit), transfer.synchronize())
            assertEquals(1L, vfs.cursor(sourceId))
            assertEquals(chatLog, chat.changeLogCount())
            verifyDomainRows(db)
            assertEquals(remote.changeInventory(), 1, remote.changeCount())
        }
    }

    private suspend fun <T> withInstallation(
        name: String,
        remote: ApplyFixtureProvider,
        scope: CoroutineScope,
        block: suspend (Map<SharedDatabase, SqlDatabase>, SyncTransfer, String) -> T,
    ): T {
        val root = directory.resolve(name).apply { check(mkdir()) }
        return securityFactory(root.resolve("security")).use { security ->
            val settings = LocalSyncStore(root.resolve("sync"), security.credentials)
            val id = settings.getOrCreate()
            settings.save(
                SyncConfiguration("https://example.invalid", "fixture", "s230", id, ApplyFixtureProvider.INSTANCE),
                "public-fixture".toCharArray(),
            )
            fun open(owner: SharedDatabase) = DatabaseFactory.openShared(File(root, "${owner.id}.db"), owner, source)
            open(SharedDatabase.VFS).use { vfs ->
                MainSyncDatabases(vfs, ::open).use { databases ->
                    val factory = MainSyncFactory(RepositoryEncryption(), source, logger, CaptureLog.NONE) { }
                    val transfer = factory.open(
                        vfs,
                        root.resolve("vfs_blobs"),
                        settings,
                        remote.factory,
                        scope,
                        root.resolve("locks").apply { check(mkdir()) },
                        MainSyncDatabaseSources({ databases.get(SharedDatabase.MISTAKES) }, databases::all),
                    )
                    val all = databases.all()
                    // Fresh creation logs the migration's built-in deck. Establish the same pre-existing
                    // baseline on both installations before asserting that remote rows never echo.
                    all.getValue(SharedDatabase.MISTAKES).execute(
                        "UPDATE __change_log SET sync_version=1 WHERE table_name='anki_decks' AND record_id='deck_default'",
                    )
                    block(all, transfer, id)
                }
            }
        }
    }
}

private fun SqlDatabase.changeLogCount() = query("SELECT count(*) FROM __change_log") { it.long(0) }.single()
private fun SqlDatabase.countRows(table: String, key: String, value: String) =
    query("SELECT count(*) FROM $table WHERE $key=?", listOf(SqlValue.Text(value))) { it.long(0) }.single()
private fun SqlDatabase.cursor(device: String) = query(
    "SELECT seq FROM __android_sync_cursor_v2 WHERE instance=? AND device=?",
    listOf(SqlValue.Text(ApplyFixtureProvider.INSTANCE), SqlValue.Text(SyncWireCodec.deviceHash(device))),
) { it.long(0) }.singleOrNull() ?: 0L

private class ApplyFixtureProvider : ObjectProvider {
    private val objects = mutableMapOf(
        PairingService.FORMAT_KEY to """{"format_version":4,"min_client":3}""".encodeToByteArray(),
        PairingService.INSTANCE_KEY to """{"instance_id":"$INSTANCE","format_version":4}""".encodeToByteArray(),
    )

    val factory = object : ProviderFactory {
        override fun open(endpoint: String, username: String, root: String, password: CharArray): ObjectProvider = this@ApplyFixtureProvider
    }

    fun changeCount() = objects.keys.count { it.endsWith(".json.zst") }
    fun changeInventory() = objects.filterKeys { it.endsWith(".json.zst") }.values.map { wire ->
        SyncBatchCodec.decode(ZstdEngine().decode(wire)).changes.map { row ->
            (row["table_name"] as JsonValue.Text).value
        }
    }.toString()
    override suspend fun list(prefix: String): List<String> = objects.keys.filter { it.startsWith("${prefix.trimEnd('/')}/") }
    override suspend fun get(key: String) = objects[key]?.copyOf()
    override suspend fun put(key: String, value: ByteArray, ifAbsent: Boolean) {
        assertFalse(ifAbsent && key in objects)
        objects[key] = value.copyOf()
    }
    override suspend fun delete(key: String) {
        objects.remove(key)
    }

    companion object {
        const val INSTANCE = "s230-public-fixture"
    }
}

private fun seedDomainRows(databases: Map<SharedDatabase, SqlDatabase>) {
    databases.getValue(SharedDatabase.VFS).transaction {
        execute("INSERT INTO todo_lists(id,title,created_at,updated_at) VALUES('s230-list','List','2026-01-01','2026-01-01')")
        execute(
            "INSERT INTO todo_items(id,todo_list_id,title,created_at,updated_at) " +
                "VALUES('s230-todo','s230-list','Todo','2026-01-01','2026-01-01')",
        )
        execute(
            "INSERT INTO portable_media_events(event_id,device_id,kind,resource_id,logical_date,seconds," +
                "observed_at,local_version,updated_at) " +
                "VALUES('s230-event','fixture-dev','study','s230-page','2026-01-01',5,'2026-01-01T00:00:00Z',1,'2026-01-01T00:00:00Z')",
        )
        execute(
            "INSERT INTO portable_plugin_records(id,owner_id,owner_kind,namespace,record_id,package_version," +
                "source_hash,schema_version,data_json,device_id,updated_at) " +
                "VALUES(json_array('plugin.p','n','r'),'plugin.p','plugin','n','r','1'," +
                "'${"a".repeat(64)}',1,'{\"flag\":true}','dev','2026-01-01T00:00:00Z')",
        )
    }
    databases.getValue(SharedDatabase.CHAT_V2).transaction {
        execute(
            "INSERT INTO chat_v2_sessions(id,mode,title,created_at,updated_at) " +
                "VALUES('s230-chat','chat','Chat','2026-01-01','2026-01-01')",
        )
        execute("INSERT INTO chat_v2_messages(id,session_id,role,timestamp) VALUES('s230-message','s230-chat','user',9007199254740993)")
    }
    databases.getValue(SharedDatabase.MISTAKES).transaction {
        execute(
            "INSERT INTO document_tasks(id,document_id,original_document_name,segment_index," +
                "content_segment,status,anki_generation_options_json) " +
                "VALUES('s230-task','public-doc','public.txt',0,'x','Completed','{}')",
        )
        execute(
            "INSERT INTO custom_anki_templates(id,name,preview_front,preview_back," +
                "generation_prompt,front_template,back_template,css_style) " +
                "VALUES('s230-template','S230 Template','front','back','prompt','{{Front}}','{{Back}}','')",
        )
        execute("INSERT INTO anki_cards(id,task_id,front,back,template_id) VALUES('s230-card','s230-task','Front','Back','s230-template')")
    }
    databases.getValue(SharedDatabase.LLM_USAGE).execute(
        "INSERT INTO llm_usage_logs(id,timestamp,provider,model,caller_type,prompt_tokens,total_tokens) " +
            "VALUES('s230-usage','2026-01-01T00:00:00Z','fixture','model','chat',9007199254740993,9007199254740993)",
    )
}

private fun verifyDomainRows(databases: Map<SharedDatabase, SqlDatabase>) {
    fun integer(owner: SharedDatabase, sql: String) = databases.getValue(owner).query(sql) { it.long(0) }.single()
    fun text(owner: SharedDatabase, sql: String) = databases.getValue(owner).query(sql) { it.text(0) }.single()
    assertEquals("s230-list", text(SharedDatabase.VFS, "SELECT todo_list_id FROM todo_items WHERE id='s230-todo'"))
    assertEquals(5L, integer(SharedDatabase.VFS, "SELECT seconds FROM portable_media_events WHERE event_id='s230-event'"))
    val pluginFlag = "SELECT json_extract(data_json,'$.flag') FROM portable_plugin_records WHERE owner_id='plugin.p'"
    assertEquals(1L, integer(SharedDatabase.VFS, pluginFlag))
    assertEquals(9007199254740993L, integer(SharedDatabase.CHAT_V2, "SELECT timestamp FROM chat_v2_messages WHERE id='s230-message'"))
    assertEquals("s230-template", text(SharedDatabase.MISTAKES, "SELECT template_id FROM anki_cards WHERE id='s230-card'"))
    assertEquals("S230 Template", text(SharedDatabase.MISTAKES, "SELECT name FROM custom_anki_templates WHERE id='s230-template'"))
    assertEquals(9007199254740993L, integer(SharedDatabase.LLM_USAGE, "SELECT prompt_tokens FROM llm_usage_logs WHERE id='s230-usage'"))
    databases.values.forEach { owner -> assertTrue(owner.query("PRAGMA foreign_key_check") { it.text(0) }.isEmpty()) }
}

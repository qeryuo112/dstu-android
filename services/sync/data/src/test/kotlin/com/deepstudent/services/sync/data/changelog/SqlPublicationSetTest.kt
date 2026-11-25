package com.deepstudent.services.sync.data.changelog

import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.core.common.logging.LogRecord
import com.deepstudent.core.common.logging.Logger
import com.deepstudent.platform.database.DatabaseRegistry
import com.deepstudent.platform.database.MigrationSource
import com.deepstudent.platform.database.SharedDatabase
import com.deepstudent.platform.database.SqlDatabase
import com.deepstudent.platform.database.SqlValue
import com.deepstudent.services.sync.api.DomainSyncAdapter
import com.deepstudent.services.sync.api.SyncChangeReceipt
import com.deepstudent.services.sync.backend.ObjectProvider
import com.deepstudent.services.sync.backend.SyncBatchCodec
import com.deepstudent.services.sync.backend.transfer.ChangeTransfer
import com.deepstudent.services.sync.backend.transfer.PublicationOptions
import com.deepstudent.services.sync.backend.transfer.RepositorySeed
import com.deepstudent.services.sync.backend.transfer.SyncCompression
import com.deepstudent.services.sync.data.capture.SyncCaptureRegistry
import com.deepstudent.services.sync.data.transfer.SqlUploadState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SqlPublicationSetTest {
    @get:Rule val temp = TemporaryFolder()
    private val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
        .first { it.resolve("config/schema/final-lock.json").isFile }
    private val migrations = MigrationSource { root.resolve(it).readBytes() }
    private val registry by lazy { SyncCaptureRegistry.load(migrations) }

    @Test
    fun partialAcknowledgementReopensAndReusesTwoEqualIdsAndExactlyTheReservedBytes() = runBlocking {
        val remote = MemoryProvider()
        var pendingFailure = false
        remote.afterPut = { pendingFailure = true }
        val records = LogBuffer()
        val reserved = open(SharedDatabase.VFS).use { vfs ->
            open(SharedDatabase.CHAT_V2).use { chat ->
                insertResource(vfs, "local-fixture")
                chat.execute("INSERT INTO chat_v2_sessions(id,mode,created_at,updated_at) VALUES('session','general_chat','a','a')")
                selectOneLog(vfs, "resources", "local-fixture")
                selectOneLog(chat, "chat_v2_sessions", "session")
                val journal = journal(vfs)
                val set = SqlPublicationSet(
                    listOf(source(vfs, SharedDatabase.VFS), source(chat, SharedDatabase.CHAT_V2) { check(!pendingFailure) }),
                    NoIncoming,
                )
                val transfer = ChangeTransfer(remote, Compression, journal, set, DEVICE, publication = PublicationOptions(logger = records))
                assertThrows(IllegalStateException::class.java) { runBlocking { transfer.upload(4, "test-operation-1") } }
                assertEquals(0L, journal.published())
                val pending = checkNotNull(journal.pending())
                assertEquals(listOf(SyncChangeReceipt("vfs", LOG_ID), SyncChangeReceipt("chat_v2", LOG_ID)), pending.receipts)
                assertEquals(pending.version, version(vfs))
                assertEquals(0L, version(chat))
                assertArrayEquals(pending.payload, remote.objects[pending.key])
                pending
            }
        }
        open(SharedDatabase.VFS).use { vfs ->
            open(SharedDatabase.CHAT_V2).use { chat ->
                val journal = journal(vfs)
                val set = SqlPublicationSet(listOf(source(vfs, SharedDatabase.VFS), source(chat, SharedDatabase.CHAT_V2)), NoIncoming)
                val transfer = ChangeTransfer(remote, Compression, journal, set, DEVICE, publication = PublicationOptions(logger = records))
                transfer.resumePending("test-operation-2")
                assertEquals(1L, journal.published())
                assertNull(journal.pending())
                assertEquals(reserved.version, version(vfs))
                assertEquals(reserved.version, version(chat))
                assertEquals(setOf(reserved.key), remote.objects.keys)
                assertEquals(1, remote.puts)
                assertArrayEquals(reserved.payload, remote.objects[reserved.key])
            }
        }
        assertEquals(listOf("RESERVE", "READBACK", "REPLAY", "READBACK", "ACKNOWLEDGE"), records.values.map { it.text("stage") })
        assertEquals(listOf("test-operation-1", "test-operation-2"), records.values.map { it.correlationId?.value }.distinct())
        assertTrue(records.values.all { it.fields["elapsedMs"] is JsonValue.Number })
        assertFalse(records.values.any { it.fields.literal().contains("local-fixture") || it.fields.literal().contains(reserved.key) })
    }

    @Test
    fun seedUsesTheCompleteInventoryNotOnlyPendingRows() = runBlocking {
        withFourOwners { owners ->
            // Include the real migration-seeded deck in the inventory, not in the unsent fragment.
            owners.values.forEach { it.execute("UPDATE __change_log SET sync_version=6") }
            val vfs = owners.getValue(SharedDatabase.VFS)
            insertResource(vfs, "already-confirmed")
            vfs.execute("UPDATE __change_log SET sync_version=6 WHERE table_name='resources' AND record_id='already-confirmed'")
            insertResource(vfs, "pending-in-inventory")
            insertTemplates(owners.getValue(SharedDatabase.MISTAKES))
            val sources = owners.map { (owner, db) -> source(db, owner) }
            val set = SqlPublicationSet(sources, NoIncoming)
            val seed: RepositorySeed = SqlRepositorySeed(sources)
            val remote = MemoryProvider()
            val journal = journal(vfs)
            val transfer = ChangeTransfer(remote, Compression, journal, set, DEVICE, publication = PublicationOptions(seed))
            assertEquals(listOf("pending-in-inventory"), set.pending().map { JsonValue.parseObject(it.decodeToString()).text("record_id") })
            transfer.upload(4)
            val seedBatch = SyncBatchCodec.decode(remote.objects.values.single())
            val resourceKeys = seedBatch.changes.filter { it.text("table_name") == "resources" }.map { it.text("record_id") }.toSet()
            assertEquals(setOf("already-confirmed", "pending-in-inventory"), resourceKeys)
            val templates = seedBatch.changes.filter { it.text("table_name") == "custom_anki_templates" }
            assertEquals(listOf("modified-bundled"), templates.map { it.text("record_id") })
            assertEquals(1UL, seedBatch.sourceSeq)
            assertTrue(set.pending().isEmpty())
            insertResource(vfs, "after-seed")
            transfer.upload(4)
            val incremental = SyncBatchCodec.decode(remote.objects.values.last())
            assertEquals(2UL, incremental.sourceSeq)
            assertEquals(listOf("after-seed"), incremental.changes.map { it.text("record_id") })
        }
    }

    private fun open(owner: SharedDatabase): SqlDatabase =
        DatabaseRegistry.openShared(temp.root.resolve("${owner.id}.db"), owner, migrations)

    private suspend fun withFourOwners(block: suspend (Map<SharedDatabase, SqlDatabase>) -> Unit) {
        val owners = linkedMapOf<SharedDatabase, SqlDatabase>()
        try {
            SharedDatabase.entries.forEach { owners[it] = open(it) }
            block(owners)
        } finally {
            owners.values.reversed().forEach(SqlDatabase::close)
        }
    }

    private fun source(db: SqlDatabase, owner: SharedDatabase, checkCancelled: () -> Unit = {}): SqlPublicationSource =
        SqlPublicationSource(db, owner, registry.rowSyncKeys(owner), checkCancelled)

    private fun insertResource(db: SqlDatabase, id: String) {
        db.execute(
            "INSERT INTO resources(id,hash,type,created_at,updated_at) VALUES(?,?,'note',1,1)",
            listOf(SqlValue.Text(id), SqlValue.Text(id)),
        )
    }

    private fun insertTemplates(db: SqlDatabase) {
        db.execute(
            "INSERT INTO custom_anki_templates(id,name,preview_front,preview_back,generation_prompt," +
                "front_template,back_template,css_style,is_built_in,user_modified) " +
                "VALUES('unchanged-bundled','n','f','b','p','f','b','c',1,0),('modified-bundled','n2','f','b','p','f','b','c',1,1)",
        )
        db.execute("UPDATE __change_log SET sync_version=6 WHERE table_name='custom_anki_templates'")
    }

    private fun selectOneLog(db: SqlDatabase, table: String, id: String) {
        db.execute("DELETE FROM __change_log WHERE table_name<>? OR record_id<>?", listOf(SqlValue.Text(table), SqlValue.Text(id)))
        db.execute("UPDATE __change_log SET id=?", listOf(SqlValue.Integer(LOG_ID)))
    }

    private fun version(db: SqlDatabase): Long = db.query(
        "SELECT COALESCE(sync_version,0) FROM __change_log WHERE id=?",
        listOf(SqlValue.Integer(LOG_ID)),
    ) { it.long(0) }.single()

    private fun journal(db: SqlDatabase) = SqlUploadState(db, "test-repository", DEVICE)

    private class MemoryProvider : ObjectProvider {
        val objects = linkedMapOf<String, ByteArray>()
        var puts = 0
        var afterPut: () -> Unit = {}
        override suspend fun get(key: String) = objects[key]?.copyOf()
        override suspend fun put(key: String, value: ByteArray, ifAbsent: Boolean) {
            check(!ifAbsent || key !in objects)
            objects[key] = value.copyOf()
            puts++
            afterPut()
        }
        override suspend fun list(prefix: String) = objects.keys.filter { it.startsWith(prefix) }
        override suspend fun delete(key: String) {
            objects.remove(key)
        }
    }

    private class LogBuffer : Logger {
        val values = mutableListOf<LogRecord>()
        override fun log(record: LogRecord) {
            values += record
        }
    }

    private object Compression : SyncCompression {
        override fun encode(bytes: ByteArray) = bytes
        override fun decode(bytes: ByteArray) = bytes
    }

    private object NoIncoming : DomainSyncAdapter {
        override fun pending(): List<ByteArray> = emptyList()
        override fun markPublished(changeLogIds: List<Long>, version: Long) = error("must acknowledge by database owner")
        override fun cursor(device: String): Long = 0
        override fun apply(device: String, seq: Long, changes: List<ByteArray>) = error("test covers outgoing publication only")
    }
}

private fun JsonValue.Object.text(name: String): String = (this[name] as JsonValue.Text).value
private fun LogRecord.text(name: String): String = fields.text(name)
private const val DEVICE = "fixture-writer"
private const val LOG_ID = 41L

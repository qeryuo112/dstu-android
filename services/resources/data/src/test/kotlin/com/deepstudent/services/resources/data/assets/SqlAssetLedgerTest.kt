package com.deepstudent.services.resources.data.assets

import com.deepstudent.core.common.id.DeviceId
import com.deepstudent.core.common.id.Digests
import com.deepstudent.core.common.time.WallClock
import com.deepstudent.platform.database.*
import com.deepstudent.services.resources.api.*
import com.deepstudent.services.resources.api.assets.*
import com.deepstudent.services.resources.backend.AttachmentService
import com.deepstudent.services.resources.backend.assets.AssetLedgerService
import com.deepstudent.services.resources.data.SqlAttachmentRepository
import java.io.File
import java.nio.file.Files
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class SqlAssetLedgerTest {
    private val root = Files.createTempDirectory("s208-db-").toFile()
    private val repositoryRoot = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
        .first { File(it, "config/schema/final-lock.json").isFile }
    private val source = MigrationSource { repositoryRoot.resolve(it).readBytes() }
    private val vfsFile = root.resolve("vfs.db")
    private val chatFile = root.resolve("chat.db")
    private val blobs = root.resolve("blobs")
    private val clock = WallClock { Instant.parse("2026-11-20T12:00:00Z") }
    private val bytes = ByteArray(1024 * 1024) { (it % 251).toByte() }
    private val hash = Digests.sha256Hex(bytes)
    private val target = AssetTarget(AssetKey.parse("active/chat_attachments/session/$hash"), AssetDigest(hash, bytes.size.toLong()))

    @After fun cleanup() { root.deleteRecursively() }

    @Test fun realDatabasesKeepBytesThroughDeleteRestoreAndReadOnlyJournal() = runBlocking {
        var attachmentId = ""
        DatabaseFactory.openShared(vfsFile, SharedDatabase.VFS, source).use { vfs ->
            DatabaseFactory.openShared(chatFile, SharedDatabase.CHAT_V2, source).use { chat ->
                val attachments = AttachmentService(SqlAttachmentRepository(vfs, blobs, clock))
                val saved = attachments.create(CreateAttachment("same.bin", "application/octet-stream", bytes))
                attachmentId = saved.id
                insertChatReferences(chat)
                insertJournal(vfs)
                val ledger = ledger(vfs, chat)
                val initial = ledger.references(target)
                assertCounts(initial, AssetReferenceOwner.VFS_FILES, 1, 0)
                assertCounts(initial, AssetReferenceOwner.VFS_RESOURCES, 1, 0)
                assertCounts(initial, AssetReferenceOwner.CHAT_ATTACHMENTS, 1, 1)
                assertFalse(initial.completeForReclamation)
                assertTrue(AssetReferenceOwner.NOTE_HISTORY in initial.unobservedOwners)
                attachments.delete(saved.id)
                assertCounts(ledger.references(target), AssetReferenceOwner.VFS_FILES, 0, 1)
                assertEquals(0, ledger.cleanupAsset(target).removed)
                assertEquals(0, ledger.cleanupBlob(hash).removed)
                attachments.restore(saved.id)
                assertArrayEquals(bytes, attachments.get(saved.id)?.bytes)
                chat.execute("UPDATE chat_v2_attachments SET deleted_at=NULL WHERE id='chat-deleted'")
                assertCounts(ledger.references(target), AssetReferenceOwner.CHAT_ATTACHMENTS, 2, 0)
                val snapshot = ledger.deletionSnapshot()
                assertEquals(listOf(AssetJournalState.PREPARED, AssetJournalState.READY), snapshot.journal.map { it.state })
                val derived = ledger.deriveReadyTombstones(DeviceId.parse("fixture-device"))
                assertEquals(setOf(target.key.value), derived.entries.keys)
                assertEquals(snapshot.ready.single().deletedAt, derived.entries.getValue(target.key.value).deletedAt)
                assertEquals(1L, vfs.query("SELECT count(*) FROM __asset_deletion_queue") { it.long(0) }.single())
                // Simulate an already-confirmed publisher in this test only: the frozen trigger owns the transition.
                vfs.execute("DELETE FROM __asset_deletion_queue WHERE key=?", listOf(text(target.key.value)))
                vfs.execute("UPDATE __file_deletion_journal SET state='cancelled',cancelled_at='d' WHERE operation_id='prepared'")
                assertTrue(ledger.deriveReadyTombstones(DeviceId.parse("fixture-device")).entries.isEmpty())
                assertEquals(listOf(AssetJournalState.CANCELLED, AssetJournalState.PUBLISHED),
                    ledger.deletionSnapshot().journal.map { it.state })
            }
        }
        DatabaseFactory.openShared(vfsFile, SharedDatabase.VFS, source).use { vfs ->
            val attachments = AttachmentService(SqlAttachmentRepository(vfs, blobs, clock))
            assertArrayEquals(bytes, attachments.get(attachmentId)?.bytes)
            assertEquals(AssetJournalState.PUBLISHED, SqlAssetDeletionReader(vfs).read().journal.last().state)
        }
    }

    @Test fun concurrentBlobCreationAndRetentionCannotReclaimSharedContent() {
        DatabaseFactory.openShared(vfsFile, SharedDatabase.VFS, source).use { vfs ->
            val attachments = AttachmentService(SqlAttachmentRepository(vfs, blobs, clock))
            val ledger = AssetLedgerService(SqlAssetDeletionReader(vfs), emptyList(), OwnedAssetScratch(root))
            val executor = java.util.concurrent.Executors.newFixedThreadPool(2)
            val start = java.util.concurrent.CountDownLatch(1)
            try {
                val writer = executor.submit<String> {
                    start.await()
                    runBlocking {
                        var saved = ""
                        repeat(3) { saved = attachments.create(CreateAttachment("same.bin", "application/octet-stream", bytes)).id }
                        saved
                    }
                }
                val cleaner = executor.submit {
                    start.await()
                    repeat(100) {
                        assertEquals(AssetCleanupPolicy.RETAIN_SHARED_BLOB, ledger.cleanupBlob(hash).policy)
                        assertEquals(AssetCleanupPolicy.RETAIN_SHARED_ASSET, ledger.cleanupAsset(target).policy)
                    }
                }
                start.countDown()
                val id = writer.get(20, java.util.concurrent.TimeUnit.SECONDS)
                cleaner.get(20, java.util.concurrent.TimeUnit.SECONDS)
                runBlocking { assertArrayEquals(bytes, attachments.get(id)?.bytes) }
            } finally { start.countDown(); executor.shutdownNow() }
        }
    }

    @Test fun missingReferenceDatabaseFailsClosedRatherThanReportingZero() {
        val unrelated = DatabaseFactory.openLocal(root.resolve("wrong.db"))
        unrelated.use {
            assertThrows(Exception::class.java) { SqlChatAttachmentReferences(it).inspect(target) }
        }
    }

    private fun ledger(vfs: SqlDatabase, chat: SqlDatabase) = AssetLedgerService(
        SqlAssetDeletionReader(vfs), listOf(SqlVfsFilesReferences(vfs), SqlVfsResourceReferences(vfs),
            SqlChatAttachmentReferences(chat)), OwnedAssetScratch(root),
    )

    private fun insertChatReferences(db: SqlDatabase) {
        db.execute("INSERT INTO chat_v2_sessions(id,mode,created_at,updated_at) VALUES ('s','general_chat','t','t')")
        db.execute("INSERT INTO chat_v2_messages(id,session_id,role,timestamp) VALUES ('m','s','user',1)")
        db.execute("""INSERT INTO chat_v2_attachments(id,message_id,name,type,mime_type,size,status,storage_path,
            content_hash,created_at,deleted_at) VALUES ('chat-active','m','x','other','application/octet-stream',1,
            'ready',?,?, 't',NULL)""".trimIndent(), listOf(text("active/chat_attachments/elsewhere/file"), text(hash)))
        db.execute("""INSERT INTO chat_v2_attachments(id,message_id,name,type,mime_type,size,status,storage_path,
            content_hash,created_at,deleted_at) VALUES ('chat-deleted','m','x','other','application/octet-stream',1,
            'ready',?,?, 't','2026-11-20T12:00:00Z')""".trimIndent(), listOf(text(target.key.value), text("other")))
    }

    private fun insertJournal(db: SqlDatabase) {
        db.execute("""INSERT INTO __file_deletion_journal
            (operation_id,target_kind,entity_key,local_path,state,prepared_at) VALUES
            ('prepared','asset','active/anki_media/not-yet','anki_media/not-yet','prepared','a')""".trimIndent())
        db.execute("""INSERT INTO __file_deletion_journal
            (operation_id,target_kind,entity_key,local_path,state,prepared_at,ready_at) VALUES
            ('ready','asset',?,'chat_attachments/deleted','ready','b','c')""".trimIndent(), listOf(text(target.key.value)))
        db.execute("INSERT INTO __asset_deletion_queue(key,size,deleted_at,retry_count) VALUES (?,?,?,?)",
            listOf(text(target.key.value), SqlValue.Integer(bytes.size.toLong()), text("2026-11-20T12:00:00Z"), SqlValue.Integer(0)))
    }

    private fun assertCounts(report: AssetReferenceReport, owner: AssetReferenceOwner, active: Long, soft: Long) {
        assertEquals(AssetReferenceCount(owner, active, soft), report.counts.single { it.owner == owner })
    }
    private fun text(value: String): SqlValue = SqlValue.Text(value)
}

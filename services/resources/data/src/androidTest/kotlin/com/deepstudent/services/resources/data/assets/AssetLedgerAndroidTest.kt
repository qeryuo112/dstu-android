package com.deepstudent.services.resources.data.assets

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.deepstudent.core.common.id.DeviceId
import com.deepstudent.core.common.id.Digests
import com.deepstudent.core.common.logging.LogRecord
import com.deepstudent.core.common.logging.Logger
import com.deepstudent.core.common.time.SystemWallClock
import com.deepstudent.platform.database.*
import com.deepstudent.services.resources.api.*
import com.deepstudent.services.resources.api.assets.*
import com.deepstudent.services.resources.backend.AttachmentService
import com.deepstudent.services.resources.backend.assets.AssetLedgerService
import com.deepstudent.services.resources.backend.assets.AssetManifestCatalog
import com.deepstudent.services.resources.backend.assets.AssetManifestCodec
import com.deepstudent.services.resources.backend.assets.AssetTombstoneWireCodec
import com.deepstudent.services.resources.data.SqlAttachmentRepository
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AssetLedgerAndroidTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = File(context.cacheDir, "s208-device-${System.nanoTime()}").apply { mkdirs() }
    private val vfsFile = root.resolve("vfs.db")
    private val chatFile = root.resolve("chat.db")
    private val source = BundledMigrationSource(context)
    private val blobs = root.resolve("blobs")
    private val bytes = ByteArray(1024 * 1024) { (it % 251).toByte() }
    private val hash = Digests.sha256Hex(bytes)
    private val target = AssetTarget(AssetKey.parse("active/chat_attachments/session/$hash"), AssetDigest(hash, bytes.size.toLong()))
    private val logs = mutableListOf<LogRecord>()
    private val logger = object : Logger {
        override fun log(record: LogRecord) {
            logs += record
            Log.i("S208AssetLedger", record.toString())
        }
    }

    @After fun cleanup() { root.deleteRecursively() }

    @Test fun realDatabasesMultiReferenceSoftDeleteRestoreJournalAndReopen() = runBlocking {
        var id = ""
        DatabaseFactory.openShared(vfsFile, SharedDatabase.VFS, source).use { vfs ->
            DatabaseFactory.openShared(chatFile, SharedDatabase.CHAT_V2, source).use { chat ->
                val attachments = AttachmentService(SqlAttachmentRepository(vfs, blobs, SystemWallClock))
                val saved = attachments.create(CreateAttachment("s208.bin", "application/octet-stream", bytes))
                id = saved.id
                insertChat(chat)
                insertReady(vfs)
                val ledger = AssetLedgerService(SqlAssetDeletionReader(vfs), listOf(
                    SqlVfsFilesReferences(vfs), SqlVfsResourceReferences(vfs), SqlChatAttachmentReferences(chat)),
                    OwnedAssetScratch(root), logger)
                val report = ledger.references(target)
                assertEquals(AssetReferenceCount(AssetReferenceOwner.CHAT_ATTACHMENTS, 1, 1),
                    report.counts.single { it.owner == AssetReferenceOwner.CHAT_ATTACHMENTS })
                assertFalse(report.completeForReclamation)
                assertTrue(AssetReferenceOwner.REMOTE_MANIFESTS in report.unobservedOwners)
                attachments.delete(id)
                assertEquals(1L, ledger.references(target).counts.single { it.owner == AssetReferenceOwner.VFS_FILES }.recoverable)
                assertEquals(0, ledger.cleanupAsset(target).removed)
                assertEquals(0, ledger.cleanupBlob(hash).removed)
                attachments.restore(id)
                assertArrayEquals(bytes, attachments.get(id)?.bytes)
                val derived = ledger.deriveReadyTombstones(DeviceId.parse("device-fixture"))
                assertEquals(setOf(target.key.value), derived.entries.keys)
                assertEquals(derived, AssetTombstoneWireCodec.decode(AssetTombstoneWireCodec.encode(derived)))
                val scan = AssetScanEntry(target.key, target.digest, "2026-11-20T12:00:00Z")
                val manifest = AssetManifestCatalog.fromScan(listOf(scan), scan.updatedAt)
                assertEquals(scan, AssetManifestCatalog.scopedEntry(AssetManifestCodec.decode(AssetManifestCodec.encode(manifest)), target.key))
                assertEquals(1L, vfs.query("SELECT count(*) FROM __asset_deletion_queue") { it.long(0) }.single())
            }
        }
        DatabaseFactory.openShared(vfsFile, SharedDatabase.VFS, source).use { vfs ->
            assertArrayEquals(bytes, AttachmentService(SqlAttachmentRepository(vfs, blobs, SystemWallClock)).get(id)?.bytes)
            assertEquals(AssetJournalState.READY, SqlAssetDeletionReader(vfs).read().journal.single().state)
        }
        assertTrue(logs.isNotEmpty())
        assertTrue(logs.all { it.correlationId != null && it.fields["owner"] != null && it.fields["phase"] != null &&
            it.fields["state"] != null && it.fields["elapsedMs"] != null && it.fields["outcome"] != null })
        assertTrue(logs.none { it.toString().contains(hash) || it.toString().contains(target.key.value) })
    }

    @Test fun scansRealPrivateAssetRootsWithNoFollowAndBoundedHash() {
        val files = root.resolve("scan-files").apply { mkdirs() }
        val active = files.resolve("active").apply { mkdirs() }
        val media = active.resolve("anki_media").apply { mkdirs() }
        val chat = active.resolve("chat_attachments/session").apply { mkdirs() }
        val one = media.resolve("audio.wav").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val two = chat.resolve("image.bin").apply { writeBytes(ByteArray(1024 * 1024) { (it % 251).toByte() }) }
        active.resolve("images").apply { mkdirs(); resolve("ignored").writeText("not in scope") }
        java.nio.file.Files.createSymbolicLink(media.resolve("outside").toPath(), root.toPath())
        val scanner = ActiveAssetScanner(active, AssetScanLimits(maxFileBytes = 1024 * 1024))
        val ledger = AssetLedgerService({ AssetDeletionSnapshot(emptyList(), emptyList()) }, emptyList(),
            OwnedAssetScratch(root), logger, scanner = scanner)
        val inventory = ledger.scan()
        assertFalse(inventory.completeForPublication)
        assertEquals(1, inventory.skippedLinks)
        assertEquals(2, inventory.manifest.entries.size)
        assertEquals(Digests.sha256Hex(one.readBytes()), inventory.manifest.entries.getValue("active/anki_media/audio.wav").sha256)
        assertEquals(Digests.sha256Hex(two.readBytes()), inventory.manifest.entries.getValue("active/chat_attachments/session/image.bin").sha256)
        assertEquals(inventory.manifest, AssetManifestCodec.decode(AssetManifestCodec.encode(inventory.manifest)))
        val overLimit = ActiveAssetScanner(active, AssetScanLimits(maxFileBytes = 3))
        assertThrows(IllegalStateException::class.java) { overLimit.scan() }
    }

    @Test fun actualOwnedScratchCleanupSkipsForeignAndSymlinks() {
        val scratch = OwnedAssetScratch(root)
        scratch.withFile { it.writeText("live") }
        val dir = root.resolve("s208-owned-scratch")
        val stale = dir.resolve("s208-${UUID.randomUUID()}.tmp").apply { writeText("stale") }
        val foreign = dir.resolve("other.tmp").apply { writeText("keep") }
        val outside = root.resolve("outside").apply { writeText("keep") }
        val link = dir.resolve("s208-${UUID.randomUUID()}.tmp").toPath()
        java.nio.file.Files.createSymbolicLink(link, outside.toPath())
        val ledger = AssetLedgerService({ AssetDeletionSnapshot(emptyList(), emptyList()) }, emptyList(), scratch, logger)
        val result = ledger.cleanupOwnScratch()
        assertEquals(AssetCleanupPolicy.REMOVE_S208_OWN_SCRATCH, result.policy)
        assertEquals(1, result.removed)
        assertEquals(2, result.skipped)
        assertFalse(stale.exists())
        assertEquals("keep", foreign.readText())
        assertEquals("keep", outside.readText())
        assertTrue(java.nio.file.Files.isSymbolicLink(link))
    }

    private fun insertChat(db: SqlDatabase) {
        db.execute("INSERT INTO chat_v2_sessions(id,mode,created_at,updated_at) VALUES ('s','general_chat','t','t')")
        db.execute("INSERT INTO chat_v2_messages(id,session_id,role,timestamp) VALUES ('m','s','user',1)")
        db.execute("""INSERT INTO chat_v2_attachments(id,message_id,name,type,mime_type,size,status,storage_path,
            content_hash,created_at,deleted_at) VALUES ('a','m','a','other','application/octet-stream',1,'ready',?,?,'t',NULL)""".trimIndent(),
            listOf(text("active/chat_attachments/other/path"), text(hash)))
        db.execute("""INSERT INTO chat_v2_attachments(id,message_id,name,type,mime_type,size,status,storage_path,
            content_hash,created_at,deleted_at) VALUES ('b','m','b','other','application/octet-stream',1,'ready',?,?,'t','x')""".trimIndent(),
            listOf(text(target.key.value), text("other")))
    }

    private fun insertReady(db: SqlDatabase) {
        db.execute("""INSERT INTO __file_deletion_journal
            (operation_id,target_kind,entity_key,local_path,state,prepared_at,ready_at) VALUES
            ('ready','asset',?,'chat_attachments/deleted','ready','t','t')""".trimIndent(), listOf(text(target.key.value)))
        db.execute("INSERT INTO __asset_deletion_queue(key,size,deleted_at,retry_count) VALUES (?,?,?,0)",
            listOf(text(target.key.value), SqlValue.Integer(bytes.size.toLong()), text("2026-11-20T12:00:00Z")))
    }

    private fun text(value: String): SqlValue = SqlValue.Text(value)
}

package com.deepstudent.app.storage

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.deepstudent.core.common.id.DeviceId
import com.deepstudent.core.common.id.Digests
import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.core.common.logging.LogRecord
import com.deepstudent.core.common.logging.Logger
import com.deepstudent.platform.database.BundledMigrationSource
import com.deepstudent.platform.database.DatabaseRegistry
import com.deepstudent.platform.database.SharedDatabase
import com.deepstudent.platform.database.SqlDatabase
import com.deepstudent.platform.database.SqlValue
import com.deepstudent.services.resources.api.AssetDigest
import com.deepstudent.services.resources.api.AssetKey
import com.deepstudent.services.resources.api.CreateAttachment
import com.deepstudent.services.resources.api.assets.AssetCleanupPolicy
import com.deepstudent.services.resources.api.assets.AssetJournalState
import com.deepstudent.services.resources.api.assets.AssetReferenceCount
import com.deepstudent.services.resources.api.assets.AssetReferenceOwner
import com.deepstudent.services.resources.api.assets.AssetReferenceReport
import com.deepstudent.services.resources.api.assets.AssetScanException
import com.deepstudent.services.resources.api.assets.AssetScanFailure
import com.deepstudent.services.resources.api.assets.AssetTarget
import com.deepstudent.services.resources.backend.assets.AssetManifestCodec
import com.deepstudent.services.resources.backend.assets.AssetTombstoneWireCodec
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** All filesystem and database mutations are confined to one new private cache child per test. */
@RunWith(AndroidJUnit4::class)
class AssetLedgerCompositionDeviceTest {
    private val realContext = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = Files.createTempDirectory(realContext.cacheDir.toPath(), "s208-app-fixture-").toFile()
    private val files = root.resolve("files").apply { check(mkdirs()) }
    private val cache = root.resolve("cache").apply { check(mkdirs()) }
    private val context: Context = object : ContextWrapper(realContext) {
        override fun getFilesDir(): File = files
        override fun getCacheDir(): File = cache
    }
    private val bytes = ByteArray(1024 * 1024 + 101) { (it % 251).toByte() }
    private val hash = Digests.sha256Hex(bytes)
    private val target = AssetTarget(AssetKey.parse("active/chat_attachments/session/$hash"),
        AssetDigest(hash, bytes.size.toLong()))
    private val records = mutableListOf<LogRecord>()
    private val logger = object : Logger {
        override fun log(record: LogRecord) {
            records += record
            AndroidOperationLogger.log(record)
        }
    }

    @After fun cleanup() {
        check(requireNotNull(root.parentFile).canonicalFile == realContext.cacheDir.canonicalFile)
        check(root.name.startsWith("s208-app-fixture-"))
        root.deleteRecursively()
    }

    @Test fun realCompositionSharesLeasesAndPreservesReferencesAndFrozenOutbox() = runBlocking {
        val closedLedger = MainStorageResources.open(context, assetLedgerLogger = logger).use { storage ->
            val vfs = storage.databaseForSync()
            val chatFile = files.resolve("active/databases/chat_v2.db")
            val ledger = storage.assetLedger
            assertFalse(chatFile.exists())
            assertTrue(ledger.scan().manifest.entries.isEmpty())
            assertTrue(ledger.deletionSnapshot().ready.isEmpty())
            assertFalse(chatFile.exists())

            val first = storage.attachments.create(CreateAttachment("fixture-a.bin", "application/octet-stream", bytes))
            val second = storage.attachments.create(CreateAttachment("fixture-b.bin", "application/octet-stream", bytes))
            assertEquals(first.id, second.id) // S2.06 de-duplicates this VFS attachment.
            assertEquals(first.sha256, second.sha256)
            assertNotNull(first.blobHash)
            val chatAsset = files.resolve(target.key.value).apply {
                check(parentFile?.mkdirs() == true)
                writeBytes(bytes)
            }
            DatabaseRegistry.openShared(chatFile, SharedDatabase.CHAT_V2, BundledMigrationSource(context)).use { chat ->
                insertChat(chat)
                insertReady(vfs)
                val before = ledger.deletionSnapshot()
                assertEquals(AssetJournalState.READY, before.journal.single().state)
                assertEquals(target.key.value, before.ready.single().key)
                val report = ledger.references(target)
                assertCounts(report, AssetReferenceOwner.VFS_FILES, 1, 0)
                assertCounts(report, AssetReferenceOwner.VFS_RESOURCES, 1, 0)
                assertCounts(report, AssetReferenceOwner.CHAT_ATTACHMENTS, 1, 1)
                assertFalse(report.completeForReclamation)
                assertTrue(AssetReferenceOwner.REMOTE_MANIFESTS in report.unobservedOwners)
                storage.attachments.delete(first.id)
                assertCounts(ledger.references(target), AssetReferenceOwner.VFS_FILES, 0, 1)
                assertEquals(AssetCleanupPolicy.RETAIN_SHARED_ASSET, ledger.cleanupAsset(target).policy)
                assertEquals(AssetCleanupPolicy.RETAIN_SHARED_BLOB, ledger.cleanupBlob(hash).policy)
                assertNull(storage.attachments.get(second.id))
                assertArrayEquals(bytes, chatAsset.readBytes())
                storage.attachments.restore(first.id)
                chat.execute("UPDATE chat_v2_attachments SET deleted_at=NULL WHERE id='chat-deleted'")
                assertCounts(ledger.references(target), AssetReferenceOwner.VFS_FILES, 1, 0)
                assertCounts(ledger.references(target), AssetReferenceOwner.CHAT_ATTACHMENTS, 2, 0)
                assertArrayEquals(bytes, storage.attachments.get(first.id)?.bytes)
                val tombstones = ledger.deriveReadyTombstones(DeviceId.parse("s208-fixture-device"))
                assertEquals(tombstones, AssetTombstoneWireCodec.decode(AssetTombstoneWireCodec.encode(tombstones)))
                assertEquals(setOf(target.key.value), tombstones.entries.keys)
                assertEquals(before, ledger.deletionSnapshot())
                assertEquals(1L, vfs.query("SELECT COUNT(*) FROM __asset_deletion_queue") { it.long(0) }.single())
                assertThrows(IllegalArgumentException::class.java) {
                    AssetTombstoneWireCodec.decode("""{"entries":{"x":{"deleted_at":"t","device_id":"d","size":-1}}}""".toByteArray())
                }
                assertEquals(2L, chat.query("SELECT COUNT(*) FROM chat_v2_attachments") { it.long(0) }.single())
                assertEquals(1L, vfs.query("SELECT COUNT(*) FROM files") { it.long(0) }.single())
            }
            ledger
        }
        assertThrows(IllegalStateException::class.java) { closedLedger.references(target) }
        assertAudit(setOf("scan", "read_deletions", "references", "retain_asset", "retain_blob", "derive_ready"))
    }

    @Test fun scopedScannerJsonRejectsUnsafeNamesAndDeniedFileAccess() {
        val active = files.resolve("active").apply { check(mkdirs()) }
        val media = active.resolve("anki_media").apply { check(mkdirs()) }
        val chat = active.resolve("chat_attachments/session").apply { check(mkdirs()) }
        val first = media.resolve("audio.wav").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val second = chat.resolve(hash).apply { writeBytes(bytes) }
        val outside = root.resolve("outside.bin").apply { writeText("outside sentinel") }
        val ignored = active.resolve("other").apply { check(mkdirs()) }.resolve("hidden.bin").apply { writeText("ignored") }
        Files.createSymbolicLink(media.resolve("outside-link").toPath(), outside.toPath())
        MainStorageResources.open(context, assetLedgerLogger = logger).use { storage ->
            val ledger = storage.assetLedger
            val inventory = ledger.scan()
            assertFalse(inventory.completeForPublication)
            assertEquals(1, inventory.skippedLinks)
            assertEquals(setOf("active/anki_media/audio.wav", target.key.value), inventory.manifest.entries.keys)
            assertEquals(Digests.sha256Hex(first.readBytes()), inventory.manifest.entries.getValue("active/anki_media/audio.wav").sha256)
            assertEquals(hash, inventory.manifest.entries.getValue(target.key.value).sha256)
            assertEquals(inventory.manifest, AssetManifestCodec.decode(AssetManifestCodec.encode(inventory.manifest)))
            assertThrows(Exception::class.java) { AssetKey.parse("active/chat_attachments/../outside.bin") }
            assertThrows(IllegalArgumentException::class.java) {
                AssetManifestCodec.decode("""{"entries":{"x":{"sha256":"x","size":-1}}}""".toByteArray())
            }
            val invalid = media.resolve("bad:name").apply { writeText("invalid fixture") }
            try {
                val error = assertThrows(AssetScanException::class.java) { ledger.scan() }
                assertEquals(AssetScanFailure.UNSUPPORTED_NAME, error.reason)
            } finally { check(invalid.delete()) }
            val original = Files.getPosixFilePermissions(second.toPath())
            try {
                Files.setPosixFilePermissions(second.toPath(), emptySet())
                assertThrows(Exception::class.java) { ledger.scan() }
            } finally { Files.setPosixFilePermissions(second.toPath(), original) }
            assertArrayEquals(bytes, second.readBytes())
            assertEquals("outside sentinel", outside.readText())
            assertEquals("ignored", ignored.readText())
            assertFalse(files.resolve("active/databases/chat_v2.db").exists())
        }
        assertAudit(setOf("scan"))
        assertTrue(records.any { text(it, "phase") == "scan" && text(it, "state") == "failed" &&
            text(it, "outcome") == "UNSUPPORTED_NAME" })
        assertTrue(records.any { text(it, "phase") == "scan" && text(it, "state") == "failed" &&
            text(it, "outcome") == "STORAGE_IO" })
    }

    @Test fun productionLoggerAndCleanupTouchOnlyNamedOwnedScratch() {
        MainStorageResources.open(context).use { storage ->
            // Capture the default production logger from logcat.
            val ledger = storage.assetLedger
            assertEquals(0, ledger.cleanupOwnScratch().removed)
            val dir = cache.resolve("s208-owned-scratch")
            val stale = dir.resolve("s208-${UUID.randomUUID()}.tmp").apply { writeText("stale") }
            val foreign = dir.resolve("s208-not-a-uuid.tmp").apply { writeText("foreign") }
            val outside = root.resolve("outside.txt").apply { writeText("outside") }
            val sibling = cache.resolve("s208-${UUID.randomUUID()}.tmp").apply { writeText("sibling") }
            val link = dir.resolve("s208-${UUID.randomUUID()}.tmp").toPath()
            Files.createSymbolicLink(link, outside.toPath())
            val result = ledger.cleanupOwnScratch()
            assertEquals(AssetCleanupPolicy.REMOVE_S208_OWN_SCRATCH, result.policy)
            assertEquals(1, result.removed)
            assertEquals(2, result.skipped)
            assertFalse(stale.exists())
            assertEquals("foreign", foreign.readText())
            assertEquals("outside", outside.readText())
            assertEquals("sibling", sibling.readText())
            assertTrue(Files.isSymbolicLink(link))
            assertFalse(files.resolve("active/databases/chat_v2.db").exists())
        }
    }

    @Test fun brokenChatDatabaseFailsClosedInsteadOfReportingNoReferences() {
        MainStorageResources.open(context, assetLedgerLogger = logger).use { storage ->
            val chat = files.resolve("active/databases/chat_v2.db").apply { writeText("not a sqlite database") }
            assertThrows(Exception::class.java) { storage.assetLedger.references(target) }
            assertTrue(chat.exists())
            assertTrue(records.any { text(it, "phase") == "references" && text(it, "state") == "failed" })
        }
        assertAudit(setOf("references"))
    }

    private fun insertChat(db: SqlDatabase) {
        db.execute("INSERT INTO chat_v2_sessions(id,mode,created_at,updated_at) VALUES ('s','general_chat','t','t')")
        db.execute("INSERT INTO chat_v2_messages(id,session_id,role,timestamp) VALUES ('m','s','user',1)")
        db.execute("""INSERT INTO chat_v2_attachments(id,message_id,name,type,mime_type,size,status,storage_path,
            content_hash,created_at,deleted_at) VALUES ('chat-active','m','a','other','application/octet-stream',1,
            'ready',?,?,'t',NULL)""".trimIndent(), listOf(text("active/chat_attachments/other/path"), text(hash)))
        db.execute("""INSERT INTO chat_v2_attachments(id,message_id,name,type,mime_type,size,status,storage_path,
            content_hash,created_at,deleted_at) VALUES ('chat-deleted','m','b','other','application/octet-stream',1,
            'ready',?,?,'t','x')""".trimIndent(), listOf(text(target.key.value), text("different")))
    }

    private fun insertReady(vfs: SqlDatabase) {
        vfs.execute("""INSERT INTO __file_deletion_journal
            (operation_id,target_kind,entity_key,local_path,state,prepared_at,ready_at) VALUES
            ('fixture-ready','asset',?,'chat_attachments/deleted','ready','t','t')""".trimIndent(), listOf(text(target.key.value)))
        vfs.execute("INSERT INTO __asset_deletion_queue(key,size,deleted_at,retry_count) VALUES (?,?,?,0)",
            listOf(text(target.key.value), SqlValue.Integer(bytes.size.toLong()), text("2026-11-20T12:00:00Z")))
    }

    private fun assertCounts(report: AssetReferenceReport, owner: AssetReferenceOwner, active: Long, soft: Long) {
        assertEquals(AssetReferenceCount(owner, active, soft), report.counts.single { it.owner == owner })
    }
    private fun assertAudit(phases: Set<String>) {
        assertTrue(records.isNotEmpty())
        assertTrue(records.map { text(it, "phase") }.containsAll(phases))
        assertTrue(records.all { it.correlationId != null && text(it, "owner") == "services/resources" &&
            text(it, "phase").isNotBlank() && text(it, "state") in setOf("completed", "failed") &&
            it.fields["elapsedMs"] is JsonValue.Number && text(it, "outcome").isNotBlank() })
        assertTrue(records.none { it.toString().contains(hash) || it.toString().contains(target.key.value) ||
            it.toString().contains("outside sentinel") || it.toString().contains("fixture-only secret") })
    }
    private fun text(record: LogRecord, field: String): String = (record.fields[field] as? JsonValue.Text)?.value.orEmpty()
    private fun text(value: String): SqlValue = SqlValue.Text(value)
}

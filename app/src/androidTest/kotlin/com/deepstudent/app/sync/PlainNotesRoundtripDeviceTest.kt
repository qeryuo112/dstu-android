package com.deepstudent.app.sync

import android.view.WindowManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.deepstudent.app.editor.EditorTestHost
import com.deepstudent.core.common.id.Digests
import com.deepstudent.core.common.id.ResourceId
import com.deepstudent.core.common.time.SystemWallClock
import com.deepstudent.features.notes.api.EditNote
import com.deepstudent.features.notes.backend.application.NotesService
import com.deepstudent.features.notes.data.NoteResourceWriter
import com.deepstudent.features.notes.data.SqlNoteRepository
import com.deepstudent.platform.database.BundledMigrationSource
import com.deepstudent.platform.database.DatabaseFactory
import com.deepstudent.platform.database.SharedDatabase
import com.deepstudent.platform.security.AndroidKeystoreKeyWrapper
import com.deepstudent.platform.security.SecurityFactory
import com.deepstudent.services.resources.api.InlineNoteResource
import com.deepstudent.services.resources.api.CreateAttachment
import com.deepstudent.services.resources.backend.AttachmentService
import com.deepstudent.services.resources.data.SqlAttachmentRepository
import com.deepstudent.services.resources.data.SqlInlineResourceWriter
import com.deepstudent.services.resources.data.SyncBlobStore
import com.deepstudent.services.sync.api.PairingMode
import com.deepstudent.services.sync.api.PairingRequest
import com.deepstudent.services.sync.api.SyncError
import com.deepstudent.services.sync.api.SyncResult
import com.deepstudent.services.sync.backend.PairingService
import com.deepstudent.services.sync.backend.RepositoryEncryption
import com.deepstudent.services.sync.backend.SyncWireCodec
import com.deepstudent.services.sync.data.LocalSyncStore
import com.deepstudent.services.sync.data.WebDavProviderFactory
import com.deepstudent.services.sync.data.capture.SyncCaptureRegistry
import com.deepstudent.services.sync.data.transfer.DsbkSyncCryptography
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlainNotesRoundtripDeviceTest {
    @Test fun phase() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val id = requireNotNull(args.getString("testId"))
        assertEquals(id, UUID.fromString(id).toString())
        val phase = requireNotNull(args.getString("phase"))
        val repository = args.getString("repository") ?: "s1-25"
        check(repository in setOf("s1-25", "s1-26"))
        val context = instrumentation.targetContext
        val root = File(context.cacheDir.canonicalFile, "s1-25-notes-$id")
        val alias = "com.deepstudent.app.sync.roundtrip.$id"
        if (phase == "cleanup") {
            AndroidKeystoreKeyWrapper(alias).deleteKey()
            assertTrue(!root.exists() || root.deleteRecursively())
            return@runBlocking
        }
        val host = EditorTestHost()
        host.start()
        instrumentation.runOnMainSync { host.activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            SecurityFactory.create(File(root, "security"), alias).use { security ->
                val store = LocalSyncStore(File(root, "sync"), security.credentials)
                val providers = WebDavProviderFactory(allowLoopbackHttp = true)
                val encryption = RepositoryEncryption(DsbkSyncCryptography(security.dsbk))
                val endpoint = requireNotNull(args.getString("endpoint"))
                if (phase == "create") {
                    assertTrue(PairingService(store, store, providers, encryption = encryption).pair(PairingMode.CREATE,
                        publicPairRequest(endpoint, "s1-26-android-create", publicEncryptionPassword())) is SyncResult.Success)
                    return@runBlocking
                }
                if (phase == "edit") {
                    val service = PairingService(store, store, providers, encryption = encryption)
                    if (repository == "s1-26") {
                        assertEquals(SyncResult.Failure(SyncError.ENCRYPTION_PASSWORD_INCORRECT), service.pair(PairingMode.JOIN,
                            publicPairRequest(endpoint, repository, "incorrect fixture".toCharArray())))
                        assertNull(store.read())
                    }
                    val pairing = service.pair(PairingMode.JOIN, publicPairRequest(endpoint, repository,
                        if (repository == "s1-26") publicEncryptionPassword() else null))
                    assertTrue("pairing: $pairing", pairing is SyncResult.Success)
                }
                val databaseFile = File(root, "databases/vfs.db")
                check(databaseFile.parentFile!!.isDirectory || databaseFile.parentFile!!.mkdirs())
                DatabaseFactory.openShared(File(root, "databases/mistakes.db"), SharedDatabase.MISTAKES, BundledMigrationSource(context)).use { mistakes ->
                DatabaseFactory.openShared(databaseFile, SharedDatabase.VFS, BundledMigrationSource(context)).use { database ->
                    var decksOpened = false
                    val writer = SqlInlineResourceWriter()
                    val notes = NotesService(SqlNoteRepository(database, NoteResourceWriter { tx, value ->
                        writer.put(tx, InlineNoteResource(ResourceId.parse(value.resourceId), value.noteId, value.content, value.nowMillis)).value
                    }, SystemWallClock))
                    val blobRoot = File(root, "blobs")
                    val locks = File(root, "locks")
                    check(locks.isDirectory || locks.mkdirs())
                    val factory = MainSyncFactory(encryption, BundledMigrationSource(context))
                    val transfer = factory.open(database, blobRoot, store, providers, scope, locks) {
                        decksOpened = true
                        mistakes
                    }
                    if (phase in setOf("reserve", "retry-wrong", "retry-correct")) {
                        val retry = EncryptedSyncRetry(database, store, providers, encryption,
                            File(root, "s1-26-retry-witness"), "$repository-desktop")
                        val makeTransfer: (com.deepstudent.services.sync.backend.ProviderFactory) -> com.deepstudent.services.sync.api.SyncTransfer =
                            { wrapped -> factory.open(database, blobRoot, store, wrapped, scope, locks) { mistakes } }
                        when (phase) {
                            "reserve" -> {
                                val note = requireNotNull(notes.get(requireNotNull(args.getString("noteId"))))
                                notes.edit(EditNote(note.id, note.updatedAt, note.title, "Android retried batch", note.tags))
                                retry.reserve(makeTransfer)
                            }
                            "retry-wrong" -> retry.wrongPassword(makeTransfer, requireNotNull(args.getString("desktopSeq")).toLong())
                            "retry-correct" -> {
                                retry.correctPassword(makeTransfer, requireNotNull(args.getString("desktopSeq")).toLong())
                                assertEquals("Desktop second batch", notes.get(requireNotNull(args.getString("secondNote")))?.content)
                            }
                        }
                        return@runBlocking
                    }
                    suspend fun sync() {
                        val result = transfer.synchronize()
                        if (result is SyncResult.Failure) {
                            val config = requireNotNull(store.read())
                            val journal = com.deepstudent.services.sync.data.transfer.SqlUploadState(database, config.instanceId, config.deviceId)
                            try {
                                com.deepstudent.services.sync.data.transfer.SqlSyncManifest(database, config.instanceId, config.deviceId,
                                    journal, SyncCaptureRegistry.load(BundledMigrationSource(context)).checksumKeys(SharedDatabase.VFS)).snapshot()
                            } catch (failure: Exception) {
                                throw AssertionError("public fixture manifest diagnostic ${failure.javaClass.name}: ${failure.message}", failure)
                            }
                        }
                        assertTrue("$phase sync: $result state=${transfer.state.value}", result is SyncResult.Success)
                    }
                    sync()
                    if (phase == "edit") {
                        assertTrue("incoming deck route must run", decksOpened)
                        assertEquals(1L, mistakes.query("SELECT COUNT(*) FROM anki_decks WHERE id='deck_default'") { it.long(0) }.single())
                        val config = requireNotNull(store.read())
                        val cursor = database.query("SELECT seq FROM __android_sync_cursor_v2 WHERE instance=? AND device=?", listOf(
                            com.deepstudent.platform.database.SqlValue.Text(config.instanceId),
                            com.deepstudent.platform.database.SqlValue.Text(SyncWireCodec.deviceHash("$repository-desktop")),
                        )) { it.long(0) }.single()
                        assertEquals(requireNotNull(args.getString("desktopSeq")).toLong(), cursor)
                    }
                    val noteId = requireNotNull(args.getString("noteId"))
                    val expectedHash = requireNotNull(args.getString("blobHash"))
                    val blob = database.query("SELECT hash,relative_path,size FROM blobs WHERE hash=?", listOf(com.deepstudent.platform.database.SqlValue.Text(expectedHash))) {
                        Triple(it.text(0), it.text(1), it.long(2))
                    }.single()
                    val desktopBytes = requireNotNull(SyncBlobStore(blobRoot).read(blob.first, blob.second, blob.third))
                    assertEquals(1024 * 1024 + 17, desktopBytes.size)
                    assertEquals(expectedHash, Digests.sha256Hex(desktopBytes))
                    assertArrayEquals(ByteArray(1024 * 1024 + 17) { 0x61 }, desktopBytes)
                    when (phase) {
                        "edit" -> {
                            val note = requireNotNull(notes.get(noteId))
                            assertEquals("Public desktop note: 中文 😀\n", note.content)
                            assertTrue(notes.attachmentLinks(noteId).any { it.targetAvailable })
                            notes.edit(EditNote(note.id, note.updatedAt, "Android edited S1.25", "Android edited note: 中文 😀\n", note.tags))
                            val bytes = ByteArray(1024 * 1024 + 31) { 0x62 }
                            val attachments = AttachmentService(SqlAttachmentRepository(database, blobRoot, SystemWallClock))
                            val created = attachments.create(CreateAttachment("android-fixture.txt", "text/plain", bytes))
                            assertEquals(bytes.size.toLong(), created.size)
                            assertEquals(Digests.sha256Hex(bytes), created.blobHash)
                            assertArrayEquals(bytes, requireNotNull(attachments.get(created.id)).bytes)
                            val link = notes.linkAttachment(noteId, created.resourceId)
                            assertTrue(link.targetAvailable)
                            assertTrue(notes.attachmentLinks(noteId).count { it.targetAvailable } >= 2)
                            sync()
                        }
                        "delete" -> { assertNotNull(notes.delete(noteId).deletedAt); sync() }
                        "restore" -> { assertNull(notes.restore(noteId).deletedAt); sync() }
                        "repeat" -> { assertNull(requireNotNull(notes.get(noteId)).deletedAt); sync() }
                        else -> error("invalid phase")
                    }
                }
                }
            }
        } finally { scope.cancel(); host.close() }
    }
}

internal fun publicEncryptionPassword() = "public S1.26 仓库 🔐".toCharArray()

private fun publicPairRequest(endpoint: String, repository: String, password: CharArray?) =
    PairingRequest(endpoint, "public-sync-user", repository, "public-fixture-password".toCharArray(), password)

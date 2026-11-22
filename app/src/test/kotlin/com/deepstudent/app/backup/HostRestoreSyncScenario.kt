package com.deepstudent.app.backup

import com.deepstudent.app.sync.MainSyncFactory
import com.deepstudent.core.common.id.Digests
import com.deepstudent.core.common.id.ResourceId
import com.deepstudent.core.common.logging.NoopLogger
import com.deepstudent.core.common.time.SystemWallClock
import com.deepstudent.features.notes.api.CreateNote
import com.deepstudent.features.notes.api.EditNote
import com.deepstudent.features.notes.backend.application.NotesService
import com.deepstudent.features.notes.data.NoteResourceWriter
import com.deepstudent.features.notes.data.SqlNoteRepository
import com.deepstudent.platform.database.SharedDatabase
import com.deepstudent.platform.database.SqlDatabase
import com.deepstudent.platform.security.KeyWrapper
import com.deepstudent.platform.security.SecurityFactory
import com.deepstudent.services.backup.data.CommitDisposition
import com.deepstudent.services.backup.data.RestoreExecutionResult
import com.deepstudent.services.resources.api.InlineNoteResource
import com.deepstudent.services.resources.data.SqlInlineResourceWriter
import com.deepstudent.services.sync.api.PairingMode
import com.deepstudent.services.sync.api.PairingRequest
import com.deepstudent.services.sync.api.SyncResult
import com.deepstudent.services.sync.backend.PairingService
import com.deepstudent.services.sync.backend.RepositoryEncryption
import com.deepstudent.services.sync.backend.SyncWireCodec
import com.deepstudent.services.sync.data.LocalSyncStore
import com.deepstudent.services.sync.data.WebDavProviderFactory
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import org.junit.Assert.*

internal class HostRestoreSyncScenario(private val fixture: HostRestoreFixture, private val scratch: File, private val fixtureData: File) {
    private val endpoint = environment("ENDPOINT")
    private val instance = environment("INSTANCE")
    private val incoming = environment("INCOMING")
    private val original = environment("ORIGINAL")
    private val blob = environment("BLOB")
    private val providers = WebDavProviderFactory(allowLoopbackHttp = true)
    private val secretRoot = scratch.resolve("secrets")
    private val stateRoot = scratch.resolve("writer")
    private val operation = UUID.randomUUID().toString()
    private val encryption = RepositoryEncryption()
    private val operationScope = CoroutineScope(Job())

    suspend fun verify() {
        SecurityFactory.create(secretRoot, Wrapper).use { security ->
            val store = LocalSyncStore(stateRoot, security.credentials)
            val previous = pair(store)
            val zip = prepareArchive()
            assertEquals(RestoreExecutionResult.Committed(operation, CommitDisposition.NEWLY_COMMITTED), fixture.restore(zip, operationId = operation))
            assertTrue(LocalSyncStore(stateRoot, security.credentials).rotateWriterForRestore(operation))
            val cold = LocalSyncStore(stateRoot, security.credentials)
            assertNotEquals(previous.deviceId, cold.getOrCreate())
            val config = pair(cold)
            assertEquals(operation, config.deviceId)
            assertEquals(previous.instanceId, config.instanceId)
            assertTrue(cold.rotateWriterForRestore(operation))
            assertEquals(config, cold.read())
            syncChanges(cold)
        }
    }

    private fun prepareArchive(): File {
        fixtureData.resolve("frozen-desktop-vfs.db").copyTo(fixture.incomingFile(SharedDatabase.VFS))
        SharedDatabase.entries.filter { it != SharedDatabase.VFS }.forEach { fixture.incoming(it) {} }
        fixture.incoming(SharedDatabase.VFS) { db ->
            db.execute("INSERT INTO __change_log(table_name,record_id,operation) VALUES('notes','pre-restore-delete','DELETE')")
            val orphan = Digests.sha256HexUtf8("host-orphan")
            db.execute("INSERT INTO blobs(hash,relative_path,size,mime_type,ref_count,created_at) VALUES('$orphan','${orphan.take(2)}/$orphan.bin',11,'text/plain',0,1)")
        }
        return fixture.archive(mapOf(blob to fixtureData.resolve("fixture-blob").readBytes()))
    }

    private suspend fun syncChanges(store: LocalSyncStore) {
        val root = fixture.active
        try {
            fixture.open(root, SharedDatabase.VFS).use { db ->
                fixture.open(root, SharedDatabase.MISTAKES).use { decks ->
                    val captureLog = com.deepstudent.services.sync.data.capture.CaptureLog { report ->
                        report.issues.forEach { println("capture ${it.database.id} ${it.table} ${it.diagnostic}") }
                    }
                    val factory = MainSyncFactory(encryption, fixture.migrations, NoopLogger, captureLog) { println(it) }
                    val transfer = factory.open(db, root.resolve("vfs_blobs"), store, providers,
                        operationScope, scratch.resolve("locks").apply { mkdir() }) { decks }
                    val notes = notes(db)
                    assertEquals("Public desktop note: 中文 😀\n", notes.get(original)?.content)
                    assertNull(notes.get(incoming))
                    assertEquals("first sync", SyncResult.Success(Unit), transfer.synchronize())
                    assertTrue(publishedChanges(store).isEmpty())
                    assertEquals("Desktop second batch", notes.get(incoming)?.content)
                    val saved = checkNotNull(notes.get(original))
                    notes.edit(EditNote(saved.id, saved.updatedAt, saved.title, "Edited after normal restore", saved.tags))
                    notes.create(CreateNote("New on restored installation", "Created after normal restore"))
                    assertEquals("publish edits", SyncResult.Success(Unit), transfer.synchronize())
                    assertEquals(1, publishedChanges(store).size)
                    notes.delete(incoming)
                    assertEquals("publish deletion", SyncResult.Success(Unit), transfer.synchronize())
                    assertEquals(2, publishedChanges(store).size)
                }
            }
        } finally { operationScope.cancel() }
    }

    private fun notes(db: SqlDatabase): NotesService {
        val resources = SqlInlineResourceWriter()
        return NotesService(SqlNoteRepository(db, NoteResourceWriter { tx, note ->
            resources.put(tx, InlineNoteResource(ResourceId.parse(note.resourceId), note.noteId, note.content, note.nowMillis)).value
        }, SystemWallClock))
    }

    private suspend fun pair(store: LocalSyncStore) = (PairingService(store, store, providers).pair(PairingMode.JOIN,
        PairingRequest(endpoint, environment("USERNAME"), "s1-25", environment("WEBDAV").toCharArray())) as SyncResult.Success).value
        .also { assertEquals(instance, it.instanceId) }

    private suspend fun publishedChanges(store: LocalSyncStore): List<String> {
        val remote = providers.open(endpoint, environment("USERNAME"), "s1-25", environment("WEBDAV").toCharArray())
        val writer = SyncWireCodec.deviceHash(store.getOrCreate())
        return remote.list("data_governance").filter { key -> "/$writer/" in key && key.endsWith(".json.zst") }
    }

    private object Wrapper : KeyWrapper {
        override fun wrap(plaintext: ByteArray) = plaintext.copyOf()
        override fun unwrap(wrapped: ByteArray) = wrapped.copyOf()
    }
}

private fun environment(name: String): String = checkNotNull(System.getenv("DSTU_S216_HOST_$name"))

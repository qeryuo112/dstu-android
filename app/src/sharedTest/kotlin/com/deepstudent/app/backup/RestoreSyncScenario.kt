package com.deepstudent.app.backup

import com.deepstudent.app.storage.MainSyncDatabases
import com.deepstudent.app.sync.MainSyncDatabaseSources
import com.deepstudent.app.sync.MainSyncFactory
import com.deepstudent.core.common.id.Digests
import com.deepstudent.core.common.id.ResourceId
import com.deepstudent.core.common.logging.Logger
import com.deepstudent.core.common.logging.NoopLogger
import com.deepstudent.core.common.time.SystemWallClock
import com.deepstudent.features.notes.api.CreateNote
import com.deepstudent.features.notes.api.EditNote
import com.deepstudent.features.notes.backend.application.NotesService
import com.deepstudent.features.notes.data.NoteResourceWriter
import com.deepstudent.features.notes.data.SqlNoteRepository
import com.deepstudent.platform.database.SharedDatabase
import com.deepstudent.platform.database.SqlDatabase
import com.deepstudent.platform.security.SecurityPorts
import com.deepstudent.services.resources.api.InlineNoteResource
import com.deepstudent.services.resources.data.SqlInlineResourceWriter
import com.deepstudent.services.sync.api.PairingMode
import com.deepstudent.services.sync.api.PairingRequest
import com.deepstudent.services.sync.api.SyncConfiguration
import com.deepstudent.services.sync.api.SyncResult
import com.deepstudent.services.sync.backend.PairingService
import com.deepstudent.services.sync.backend.RepositoryEncryption
import com.deepstudent.services.sync.backend.SyncWireCodec
import com.deepstudent.services.sync.data.LocalSyncStore
import com.deepstudent.services.sync.data.WebDavProviderFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import java.io.File

internal class RestoreSyncScenario(
    private val fixture: RestoreSqlFixture,
    private val scratch: File,
    private val fixtureData: File,
    private val logger: Logger = NoopLogger,
    private val applyAttachments: RestoreApplyAttachments? = null,
    private val parameters: (String) -> String,
) {
    private val endpoint = parameters("ENDPOINT")
    private val instance = parameters("INSTANCE")
    private val incoming = parameters("INCOMING")
    private val original = parameters("ORIGINAL")
    private val blob = parameters("BLOB")
    private val providers = WebDavProviderFactory(allowLoopbackHttp = true)
    private val encryption = RepositoryEncryption()

    data class RestoredSlot(val root: File, val operation: String)

    suspend fun verify(stateRoot: File, openSecurity: () -> SecurityPorts, restore: suspend (File) -> RestoredSlot) {
        val previous = openSecurity().use { security -> pair(LocalSyncStore(stateRoot, security.credentials)) }
        val zip = prepareArchive()
        val restored = restore(zip)
        openSecurity().use { security ->
            val cold = LocalSyncStore(stateRoot, security.credentials)
            assertNull("old pairing must not survive ordinary restore", cold.read())
            assertNotEquals(previous.deviceId, cold.getOrCreate())
            val config = pair(cold)
            assertEquals(restored.operation, config.deviceId)
            assertEquals(previous.instanceId, config.instanceId)
            assertTrue(cold.rotateWriterForRestore(restored.operation))
            assertEquals(config, LocalSyncStore(stateRoot, security.credentials).read())
            syncChanges(cold, restored.root)
        }
    }

    private fun prepareArchive(): File {
        fixtureData.resolve("frozen-desktop-vfs.db").copyTo(fixture.incomingFile(SharedDatabase.VFS))
        SharedDatabase.entries.filter { it != SharedDatabase.VFS }.forEach { fixture.incoming(it) {} }
        fixture.incoming(SharedDatabase.VFS) { db ->
            db.execute("INSERT INTO __change_log(table_name,record_id,operation) VALUES('notes','pre-restore-delete','DELETE')")
            val orphan = Digests.sha256HexUtf8("host-orphan")
            db.execute(
                "INSERT INTO blobs(hash,relative_path,size,mime_type,ref_count,created_at) " +
                    "VALUES('$orphan','${orphan.take(2)}/$orphan.bin',11,'text/plain',0,1)",
            )
        }
        return fixture.archive(mapOf(blob to fixtureData.resolve("fixture-blob").readBytes()))
    }

    private suspend fun syncChanges(store: LocalSyncStore, root: File) {
        assertArrayEquals(fixtureData.resolve("fixture-blob").readBytes(), root.resolve("vfs_blobs/$blob").readBytes())
        val operationScope = CoroutineScope(Job())
        try {
            fixture.open(root, SharedDatabase.VFS).use { db ->
                MainSyncDatabases(db) { fixture.open(root, it) }.use { sources ->
                    val captureLog = com.deepstudent.services.sync.data.capture.CaptureLog { report ->
                        report.issues.forEach { println("capture ${it.database.id} ${it.table} ${it.diagnostic}") }
                    }
                    val factory = MainSyncFactory(encryption, fixture.migrations, logger, captureLog) { println(it) }
                    val transfer = factory.open(
                        db,
                        root.resolve("vfs_blobs"),
                        store,
                        providers,
                        operationScope,
                        scratch.resolve("locks").apply { mkdir() },
                        MainSyncDatabaseSources({ sources.get(SharedDatabase.MISTAKES) }, sources::all),
                    )
                    val notes = notes(db)
                    assertEquals("Public desktop note: 中文 😀\n", notes.get(original)?.content)
                    assertNull(notes.get(incoming))
                    assertEquals("first sync", SyncResult.Success(Unit), transfer.synchronize())
                    assertTrue(publishedChanges(store).isEmpty())
                    if (applyAttachments == null) {
                        assertEquals("Desktop second batch", notes.get(incoming)?.content)
                    } else {
                        applyAttachments.verifyIncoming(db, root, notes, incoming)
                    }
                    val saved = checkNotNull(notes.get(original))
                    notes.edit(EditNote(saved.id, saved.updatedAt, saved.title, "Edited after normal restore", saved.tags))
                    notes.create(CreateNote("New on restored installation", "Created after normal restore"))
                    val outbound = applyAttachments?.createOutgoing(db, root, notes, original)
                    assertEquals("publish edits", SyncResult.Success(Unit), transfer.synchronize())
                    assertEquals(1, publishedChanges(store).size)
                    if (outbound != null) {
                        checkNotNull(applyAttachments).awaitDesktopReadback()
                        outbound.rename()
                        assertEquals("publish asset rename", SyncResult.Success(Unit), transfer.synchronize())
                        assertEquals(2, publishedChanges(store).size)
                        outbound.delete()
                    }
                    notes.delete(incoming)
                    assertEquals("publish deletion", SyncResult.Success(Unit), transfer.synchronize())
                    val count = if (outbound == null) 2 else 3
                    assertEquals(count, publishedChanges(store).size)
                    if (outbound != null) {
                        assertEquals("repeat after deletion", SyncResult.Success(Unit), transfer.synchronize())
                        assertEquals(count, publishedChanges(store).size)
                    }
                }
            }
        } finally {
            operationScope.cancel()
        }
    }

    private fun notes(db: SqlDatabase): NotesService {
        val resources = SqlInlineResourceWriter()
        val writer = NoteResourceWriter { tx, note ->
            val resource = InlineNoteResource(ResourceId.parse(note.resourceId), note.noteId, note.content, note.nowMillis)
            resources.put(tx, resource).value
        }
        return NotesService(SqlNoteRepository(db, writer, SystemWallClock))
    }

    private suspend fun pair(store: LocalSyncStore): SyncConfiguration {
        val service = PairingService(store, store, providers, logger)
        val request = PairingRequest(endpoint, parameters("USERNAME"), "s1-25", parameters("WEBDAV").toCharArray())
        return when (val result = service.pair(PairingMode.JOIN, request)) {
            is SyncResult.Failure -> error("Pairing failed: ${result.error}")
            is SyncResult.Success -> result.value.also { assertEquals(instance, it.instanceId) }
        }
    }

    private suspend fun publishedChanges(store: LocalSyncStore): List<String> {
        val remote = providers.open(
            endpoint,
            parameters("USERNAME"),
            "s1-25",
            parameters("WEBDAV").toCharArray(),
        )
        val writer = SyncWireCodec.deviceHash(store.getOrCreate())
        return remote.list("data_governance").filter { key -> "/$writer/" in key && key.endsWith(".json.zst") }
    }
}

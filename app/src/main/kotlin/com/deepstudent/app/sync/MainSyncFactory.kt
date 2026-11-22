package com.deepstudent.app.sync

import com.deepstudent.app.storage.AndroidOperationLogger
import com.deepstudent.core.common.logging.Logger
import com.deepstudent.engines.zstd.ZstdEngine
import com.deepstudent.features.cards.data.AncillaryDeckApplier
import com.deepstudent.features.notes.data.NotesPendingSource
import com.deepstudent.features.notes.data.SqlNotesSyncAdapter
import com.deepstudent.platform.database.MigrationSource
import com.deepstudent.platform.database.SharedDatabase
import com.deepstudent.platform.database.SqlDatabase
import com.deepstudent.services.resources.data.SyncBlobStore
import com.deepstudent.services.resources.data.assets.presentVfsBlobReferences
import com.deepstudent.services.sync.api.SyncConfiguration
import com.deepstudent.services.sync.api.SyncTransfer
import com.deepstudent.services.sync.backend.ObjectProvider
import com.deepstudent.services.sync.backend.ProviderFactory
import com.deepstudent.services.sync.backend.RepositoryEncryption
import com.deepstudent.services.sync.backend.SyncPayloadCodec
import com.deepstudent.services.sync.backend.transfer.BlobTransfer
import com.deepstudent.services.sync.backend.transfer.ChangeTransfer
import com.deepstudent.services.sync.backend.transfer.DeviceManifests
import com.deepstudent.services.sync.backend.transfer.DomainIncomingRoute
import com.deepstudent.services.sync.backend.transfer.SyncPipeline
import com.deepstudent.services.sync.backend.transfer.RemoteGate
import com.deepstudent.services.sync.backend.transfer.RoutedDomainSyncAdapter
import com.deepstudent.services.sync.backend.transfer.SyncBlob
import com.deepstudent.services.sync.backend.transfer.SyncBlobs
import com.deepstudent.services.sync.backend.transfer.SyncCompression
import com.deepstudent.services.sync.data.LocalSyncStore
import com.deepstudent.services.sync.data.transfer.SqlSyncManifest
import com.deepstudent.services.sync.data.transfer.SqlUploadState
import com.deepstudent.services.sync.data.capture.CaptureLog
import com.deepstudent.services.sync.data.capture.SyncCaptureRegistry
import com.deepstudent.services.sync.data.capture.SyncCategory
import com.deepstudent.services.sync.data.changelog.ChangeLogReader
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ensureActive

internal class MainSyncFactory(
    private val encryption: RepositoryEncryption,
    source: MigrationSource,
    private val logger: Logger = AndroidOperationLogger,
    captureLog: CaptureLog = androidCaptureLog,
    captureDiagnostic: (String) -> Unit = ::androidCaptureDiagnostic,
) {
    private val capture = SyncCaptureGuard(captureLog, captureDiagnostic) { SyncCaptureRegistry.load(source) }

    fun open(
        database: SqlDatabase,
        blobsDirectory: File,
        configuration: LocalSyncStore,
        providers: ProviderFactory,
        scope: CoroutineScope,
        lockDirectory: File,
        incomingDecksDatabase: () -> SqlDatabase,
    ): SyncTransfer = ConfiguredSyncTransfer(
        configuration, providers, scope, SyncRunGuards(lockDirectory) { capture.requireReady(SharedDatabase.VFS, database, noteTables) },
        encryption, logger,
    ) { config, provider, codec ->
        create(database, blobsDirectory, config, provider, codec, incomingDecksDatabase)
    }

    private suspend fun create(
        database: SqlDatabase,
        blobsDirectory: File,
        config: SyncConfiguration,
        provider: ObjectProvider,
        codec: SyncPayloadCodec,
        incomingDecksDatabase: () -> SqlDatabase,
    ): SyncTransfer {
        val context = kotlinx.coroutines.currentCoroutineContext()
        val files = SyncBlobStore(blobsDirectory)
        val notes = notesAdapter(database, config, files) { context.ensureActive() }
        var validatedDecks: SqlDatabase? = null
        val adapter = RoutedDomainSyncAdapter(notes, "vfs", noteTables, listOf(
            DomainIncomingRoute("mistakes", incomingDeckTables) { rows ->
                context.ensureActive()
                val databaseForDecks = validatedDecks ?: incomingDecksDatabase().also {
                    capture.requireReady(SharedDatabase.MISTAKES, it, incomingDeckTables)
                    validatedDecks = it
                }
                AncillaryDeckApplier(databaseForDecks, logger, { context.ensureActive() }).apply(rows)
                context.ensureActive()
            },
        ))
        val journal = SqlUploadState(database, config.instanceId, config.deviceId)
        val manifests = DeviceManifests(provider, codec)
        val snapshot = SqlSyncManifest(database, config.instanceId, config.deviceId, journal, capture.registry.checksumKeys(SharedDatabase.VFS))
        return SyncPipeline(ChangeTransfer(provider, compression(), journal, adapter, config.deviceId, codec),
            BlobTransfer(provider, DatabaseSyncBlobs(database, files), codec), {
                val reader = RemoteGate(provider, config.instanceId).minimumReader()
                manifests.validateSchemas(capture.registry.schemaVersions())
                reader
            }, { manifests.publish(snapshot.snapshot()) }, logger)
    }

    private fun notesAdapter(
        database: SqlDatabase, config: SyncConfiguration, files: SyncBlobStore, checkCancelled: () -> Unit,
    ): SqlNotesSyncAdapter {
        val reader = ChangeLogReader(database, SharedDatabase.VFS.id, { table ->
            capture.registry.find(SharedDatabase.VFS, table)?.takeIf { it.category == SyncCategory.RowSync && table in noteTables }
                ?.logicalPrimaryKey
        }, logger, preserveVaultClocks = true)
        return SqlNotesSyncAdapter(database, config.instanceId, { hash, path, size -> files.read(hash, path, size) != null },
            logger, checkCancelled, NotesPendingSource { enrich -> reader.readAll(checkCancelled, enrich) })
    }

    private fun compression(): SyncCompression {
        val engine = ZstdEngine()
        return object : SyncCompression {
            override fun encode(bytes: ByteArray): ByteArray = engine.encode(bytes)
            override fun decode(bytes: ByteArray): ByteArray = engine.decode(bytes)
        }
    }

    private val noteTables = setOf("resources", "notes", "folders", "folder_items", "files", "blobs",
        "note_document_formats", "note_document_revisions", "note_learning_relations")
    private val incomingDeckTables = setOf("anki_decks")
}

internal class DatabaseSyncBlobs(private val database: SqlDatabase, private val files: SyncBlobStore) : SyncBlobs {
    override fun list(): List<SyncBlob> = database.readSnapshot {
        val referenced by lazy { presentVfsBlobReferences(this) }
        query("SELECT hash,relative_path,size,created_at,ref_count FROM blobs ORDER BY hash") { row ->
            SyncBlob(row.text(0), row.text(1), row.long(2), java.time.Instant.ofEpochMilli(row.long(3)).toString()) to row.long(4)
        }.mapNotNull { (blob, count) ->
            blob.takeUnless { count == 0L && !files.exists(it.hash, it.relativePath) && it.hash !in referenced }
        }
    }
    override fun read(blob: SyncBlob): ByteArray? = files.read(blob.hash, blob.relativePath, blob.size)
    override fun install(blob: SyncBlob, bytes: ByteArray) = files.install(blob.hash, blob.relativePath, blob.size, bytes)
}

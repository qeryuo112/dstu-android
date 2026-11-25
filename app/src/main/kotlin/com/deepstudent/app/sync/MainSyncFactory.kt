package com.deepstudent.app.sync

import com.deepstudent.app.storage.AndroidOperationLogger
import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.core.common.logging.Logger
import com.deepstudent.engines.zstd.ZstdEngine
import com.deepstudent.features.cards.data.AncillaryDeckApplier
import com.deepstudent.features.notes.data.NotesPendingSource
import com.deepstudent.features.notes.data.SqlNotesSyncAdapter
import com.deepstudent.platform.database.MigrationSource
import com.deepstudent.platform.database.SharedDatabase
import com.deepstudent.platform.database.SqlDatabase
import com.deepstudent.platform.database.SqlOperations
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
import com.deepstudent.services.sync.backend.transfer.PublicationOptions
import com.deepstudent.services.sync.backend.transfer.RemoteGate
import com.deepstudent.services.sync.backend.transfer.RoutedDomainSyncAdapter
import com.deepstudent.services.sync.backend.transfer.SyncBlob
import com.deepstudent.services.sync.backend.transfer.SyncBlobs
import com.deepstudent.services.sync.backend.transfer.SyncCompression
import com.deepstudent.services.sync.backend.transfer.SyncPipeline
import com.deepstudent.services.sync.data.LocalSyncStore
import com.deepstudent.services.sync.data.apply.ImmutableMediaEventPolicy
import com.deepstudent.services.sync.data.apply.IncomingApplyCallbacks
import com.deepstudent.services.sync.data.apply.IncomingBatchCoordinator
import com.deepstudent.services.sync.data.apply.IncomingDomain
import com.deepstudent.services.sync.data.apply.IncomingSpecialRowHandler
import com.deepstudent.services.sync.data.apply.IncomingTransactionDatabase
import com.deepstudent.services.sync.data.apply.PluginDataPolicy
import com.deepstudent.services.sync.data.apply.PrimaryIncomingAdapter
import com.deepstudent.services.sync.data.apply.RegisteredIncomingRows
import com.deepstudent.services.sync.data.apply.RegisteredSqlRowApplier
import com.deepstudent.services.sync.data.apply.SqlIncomingOwner
import com.deepstudent.services.sync.data.capture.CaptureLog
import com.deepstudent.services.sync.data.capture.SyncCaptureRegistry
import com.deepstudent.services.sync.data.capture.SyncCategory
import com.deepstudent.services.sync.data.changelog.ChangeLogReader
import com.deepstudent.services.sync.data.transfer.DatabaseManifestScope
import com.deepstudent.services.sync.data.transfer.SqlSyncManifest
import com.deepstudent.services.sync.data.transfer.SqlUploadState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ensureActive
import java.io.File

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
    ): SyncTransfer = open(
        database,
        blobsDirectory,
        configuration,
        providers,
        scope,
        lockDirectory,
        MainSyncDatabaseSources(incomingDecksDatabase),
    )

    fun open(
        database: SqlDatabase,
        blobsDirectory: File,
        configuration: LocalSyncStore,
        providers: ProviderFactory,
        scope: CoroutineScope,
        lockDirectory: File,
        databases: MainSyncDatabaseSources,
    ): SyncTransfer = ConfiguredSyncTransfer(
        configuration,
        providers,
        scope,
        SyncRunGuards(lockDirectory) { requireCapture(database, databases.all) },
        encryption,
        logger,
    ) { config, provider, codec ->
        val storage =
            RunSyncStorage(
                database,
                blobsDirectory,
                File(lockDirectory, "transfer-stage"),
                databases.incomingDecks,
                databases.all?.invoke(),
            )
        create(storage, config, provider, codec)
    }

    private fun requireCapture(vfs: SqlDatabase, full: (() -> Map<SharedDatabase, SqlDatabase>)?) {
        if (full == null) {
            capture.requireReady(SharedDatabase.VFS, vfs, noteTables)
        } else {
            for ((owner, db) in full()) capture.requireReady(owner, db, capture.registry.rowSyncKeys(owner).keys)
        }
    }

    private suspend fun create(
        storage: RunSyncStorage,
        config: SyncConfiguration,
        provider: ObjectProvider,
        codec: SyncPayloadCodec,
    ): SyncTransfer {
        val context = kotlinx.coroutines.currentCoroutineContext()
        val database = storage.vfs
        val files = SyncBlobStore(storage.blobsDirectory)
        val transactionDatabase = IncomingTransactionDatabase(database)
        val notes = notesAdapter(transactionDatabase, config, files) { context.ensureActive() }
        val incoming = storage.all?.let { databases ->
            fullIncoming(databases, transactionDatabase, notes, config) { context.ensureActive() }
        } ?: legacyIncoming(storage, notes) { context.ensureActive() }
        val publication = storage.all?.let { databases ->
            MainPublicationComposition(databases, capture.registry, logger) { context.ensureActive() }
        }
        val adapter = publication?.combine(incoming, notes, noteTables) ?: incoming
        val seed = publication?.seed(config.requiresInitialSeed)
        val journal = SqlUploadState(database, config.instanceId, config.deviceId)
        val manifests = DeviceManifests(provider, codec)
        val snapshot =
            SqlSyncManifest(
                database,
                config.instanceId,
                config.deviceId,
                journal,
                capture.registry.checksumKeys(SharedDatabase.VFS),
                storage.all?.map { (owner, db) -> DatabaseManifestScope(owner.id, db, capture.registry.rowSyncKeys(owner)) },
            )
        return SyncPipeline(
            ChangeTransfer(provider, compression(), journal, adapter, config.deviceId, codec, PublicationOptions(seed, logger)),
            BlobTransfer(provider, DatabaseSyncBlobs(database, files), codec, storage.stage),
            {
                val reader = RemoteGate(provider, config.instanceId).minimumReader()
                manifests.validateSchemas(capture.registry.schemaVersions())
                reader
            },
            { manifests.publish(snapshot.snapshot()) },
            logger,
        )
    }

    private fun legacyIncoming(storage: RunSyncStorage, notes: SqlNotesSyncAdapter, check: () -> Unit): RoutedDomainSyncAdapter {
        var validatedDecks: SqlDatabase? = null
        return RoutedDomainSyncAdapter(
            notes,
            "vfs",
            noteTables,
            listOf(
                DomainIncomingRoute("mistakes", incomingDeckTables) { rows ->
                    check()
                    val databaseForDecks = validatedDecks ?: storage.incomingDecks().also {
                        capture.requireReady(SharedDatabase.MISTAKES, it, incomingDeckTables)
                        validatedDecks = it
                    }
                    AncillaryDeckApplier(databaseForDecks, logger, check).apply(rows)
                    check()
                },
            ),
        )
    }

    private fun fullIncoming(
        databases: Map<SharedDatabase, SqlDatabase>,
        primaryDatabase: IncomingTransactionDatabase,
        notes: SqlNotesSyncAdapter,
        config: SyncConfiguration,
        check: () -> Unit,
    ): IncomingBatchCoordinator {
        val vfs = RegisteredIncomingRows(
            SharedDatabase.VFS,
            capture.registry,
            MainIncomingDomains.vfs,
            mapOf("portable_media_events" to ImmutableMediaEventPolicy, "portable_plugin_records" to PluginDataPolicy),
        )
        require(noteTables + vfs.tables == capture.registry.rowSyncKeys(SharedDatabase.VFS).keys)
        val vfsRows = RegisteredSqlRowApplier(vfs)
        val primary = PrimaryIncomingAdapter(notes, primaryDatabase, noteTables, vfsRows, check)
        val routes = listOf(
            incomingOwner(SharedDatabase.CHAT_V2, databases.getValue(SharedDatabase.CHAT_V2), MainIncomingDomains.chat, check),
            incomingOwner(
                SharedDatabase.MISTAKES,
                databases.getValue(SharedDatabase.MISTAKES),
                MainIncomingDomains.mistakes,
                check,
                deckOwner(databases.getValue(SharedDatabase.MISTAKES), check),
            ),
            incomingOwner(SharedDatabase.LLM_USAGE, databases.getValue(SharedDatabase.LLM_USAGE), MainIncomingDomains.usage, check),
        )
        return IncomingBatchCoordinator(
            primary,
            SharedDatabase.VFS,
            noteTables + vfs.tables,
            routes,
            capture.registry,
            config.instanceId,
            callbacks = IncomingApplyCallbacks(validatePrimary = { rows ->
                val (noteRows, otherRows) = rows.partition { bytes ->
                    check()
                    incomingTable(bytes) in noteTables
                }
                notes.validateIncoming(noteRows)
                vfsRows.validate(otherRows)
            }, checkCancelled = check, logger = logger),
        )
    }

    private fun incomingOwner(
        owner: SharedDatabase,
        database: SqlDatabase,
        domains: List<IncomingDomain>,
        check: () -> Unit,
        deck: IncomingSpecialRowHandler? = null,
    ): SqlIncomingOwner {
        val registration = RegisteredIncomingRows(owner, capture.registry, domains)
        require(registration.tables == capture.registry.rowSyncKeys(owner).keys)
        val applier = RegisteredSqlRowApplier(registration)
        fun split(rows: List<ByteArray>) = rows.partition { bytes ->
            deck != null && incomingTable(bytes) == "anki_decks"
        }
        return SqlIncomingOwner(owner, { database }, registration.tables, { rows ->
            val (decks, others) = split(rows)
            deck?.validate(decks)
            applier.validate(others)
        }) { rows ->
            val (decks, others) = split(rows)
            deck?.apply(this, decks)
            applier.apply(this, others, check)
        }
    }

    private fun incomingTable(bytes: ByteArray): String? =
        (JsonValue.parseObject(bytes.decodeToString(throwOnInvalidSequence = true))["table_name"] as? JsonValue.Text)?.value

    private fun deckOwner(database: SqlDatabase, check: () -> Unit): IncomingSpecialRowHandler {
        val view = IncomingTransactionDatabase(database)
        val applier = AncillaryDeckApplier(view, logger, check)
        return object : IncomingSpecialRowHandler {
            override fun validate(rows: List<ByteArray>) = applier.validateIncoming(rows)
            override fun apply(sql: SqlOperations, rows: List<ByteArray>) {
                if (rows.isNotEmpty()) view.withCurrent(sql) { applier.apply(rows) }
            }
        }
    }

    private fun notesAdapter(
        database: SqlDatabase,
        config: SyncConfiguration,
        files: SyncBlobStore,
        checkCancelled: () -> Unit,
    ): SqlNotesSyncAdapter {
        val reader = ChangeLogReader(database, SharedDatabase.VFS.id, { table ->
            capture.registry.find(SharedDatabase.VFS, table)?.takeIf { it.category == SyncCategory.RowSync && table in noteTables }
                ?.logicalPrimaryKey
        }, logger, preserveVaultClocks = true)
        return SqlNotesSyncAdapter(
            database,
            config.instanceId,
            { hash, path, size ->
                files.verifyPresent(hash, path, size, checkCancelled)
            },
            logger,
            checkCancelled,
            NotesPendingSource { enrich -> reader.readAll(checkCancelled, enrich) },
        )
    }

    private fun compression(): SyncCompression {
        val engine = ZstdEngine()
        return object : SyncCompression {
            override fun encode(bytes: ByteArray): ByteArray = engine.encode(bytes)
            override fun decode(bytes: ByteArray): ByteArray = engine.decode(bytes)
        }
    }

    private val noteTables = setOf(
        "resources", "notes", "folders", "folder_items", "files", "blobs",
        "note_document_formats", "note_document_revisions", "note_learning_relations",
    )
    private val incomingDeckTables = setOf("anki_decks")
}

internal class MainSyncDatabaseSources(val incomingDecks: () -> SqlDatabase, val all: (() -> Map<SharedDatabase, SqlDatabase>)? = null)

private data class RunSyncStorage(
    val vfs: SqlDatabase,
    val blobsDirectory: File,
    val stage: File,
    val incomingDecks: () -> SqlDatabase,
    val all: Map<SharedDatabase, SqlDatabase>?,
)

internal class DatabaseSyncBlobs(private val database: SqlDatabase, private val files: SyncBlobStore) : SyncBlobs {
    override fun list(): List<SyncBlob> = database.readSnapshot {
        val referenced by lazy { presentVfsBlobReferences(this) }
        query("SELECT hash,relative_path,size,created_at,ref_count FROM blobs ORDER BY hash") { row ->
            SyncBlob(row.text(0), row.text(1), row.long(2), java.time.Instant.ofEpochMilli(row.long(3)).toString()) to row.long(4)
        }.mapNotNull { (blob, count) ->
            blob.takeUnless { count == 0L && !files.exists(it.hash, it.relativePath) && it.hash !in referenced }
        }
    }
    override fun copyTo(blob: SyncBlob, output: java.io.OutputStream, check: () -> Unit): Boolean =
        files.copyVerified(blob.hash, blob.relativePath, blob.size, output, check)

    override fun installFrom(blob: SyncBlob, input: java.io.InputStream, check: () -> Unit) =
        files.installVerified(blob.hash, blob.relativePath, blob.size, input, check)

    override fun read(blob: SyncBlob): ByteArray? = files.read(blob.hash, blob.relativePath, blob.size)
    override fun install(blob: SyncBlob, bytes: ByteArray) = files.install(blob.hash, blob.relativePath, blob.size, bytes)
}

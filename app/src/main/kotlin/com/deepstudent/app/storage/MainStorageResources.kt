package com.deepstudent.app.storage

import android.content.Context
import android.util.Log
import com.deepstudent.app.graph.MainProcessResources
import com.deepstudent.core.common.id.ResourceId
import com.deepstudent.core.common.logging.Logger
import com.deepstudent.core.common.time.SystemWallClock
import com.deepstudent.features.notes.api.Notes
import com.deepstudent.features.notes.backend.application.NotesService
import com.deepstudent.features.notes.data.NoteResourceWriter
import com.deepstudent.features.notes.data.NoteTaskCancellations
import com.deepstudent.features.notes.data.SqlNoteRepository
import com.deepstudent.features.notes.data.SqlNoteTaskStore
import com.deepstudent.platform.database.BundledMigrationSource
import com.deepstudent.platform.database.DatabaseRegistry
import com.deepstudent.platform.database.MigrationMaintenance
import com.deepstudent.platform.database.SharedDatabase
import com.deepstudent.platform.database.SqlDatabase
import com.deepstudent.platform.network.NetworkTransport
import com.deepstudent.platform.network.OkHttpNetworkTransport
import com.deepstudent.services.resources.api.Attachments
import com.deepstudent.services.resources.api.InlineNoteResource
import com.deepstudent.services.resources.api.WorkspaceFiles
import com.deepstudent.services.resources.api.assets.AssetLedger
import com.deepstudent.services.resources.backend.AttachmentService
import com.deepstudent.services.resources.data.SqlAttachmentRepository
import com.deepstudent.services.resources.data.SqlInlineResourceWriter
import java.io.File

/** Main-process composition only. Domain consumers receive the narrow public ports. */
internal class MainStorageResources private constructor(
    private val database: SqlDatabase,
    private val notePorts: MainNotePorts,
    private val assetPorts: MainAssetPorts,
    val network: NetworkTransport,
    private val incomingDecksDatabase: Lazy<SqlDatabase>,
    private val slot: StorageSlot,
) : MainProcessResources {
    override val description: String = "main-storage"
    val notes: Notes get() = notePorts.notes
    val noteTasks: SqlNoteTaskStore get() = notePorts.tasks
    val noteCancellations: NoteTaskCancellations get() = notePorts.cancellations
    val attachments: Attachments get() = assetPorts.attachments
    val documentTransfers: MainDocumentTransfers get() = assetPorts.documentTransfers
    val workspaceFiles: WorkspaceFiles get() = assetPorts.workspaceFiles
    val assetLedger: AssetLedger get() = assetPorts.ledger
    val capabilityEpochs get() = assetPorts.capabilityEpochs
    val activeRoot: File get() = slot.root

    internal fun databaseForSync(): SqlDatabase = database
    internal fun incomingDecksDatabaseForSync(): SqlDatabase = incomingDecksDatabase.value

    override fun close() {
        // Release the chat lease before its borrowed VFS lease, and close all ports before unpinning the slot.
        slot.use { closeActiveResources() }
    }

    private fun closeActiveResources() = database.use {
        val decks = if (incomingDecksDatabase.isInitialized()) incomingDecksDatabase.value else null
        decks.use { network.use { assetPorts.close() } }
    }

    companion object {
        fun openManaged(context: Context, access: MainDataSpaceAccess): MainStorageResources =
            openActive(context, StorageSlot(access.activeRoot, null), access.migration, AndroidOperationLogger)

        /** Isolated owners (including fixtures) hold their own slot lease. */
        fun open(
            context: Context,
            maintenance: MigrationMaintenance? = null,
            assetLedgerLogger: Logger = AndroidOperationLogger,
        ): MainStorageResources {
            val access = MainDataSpaceAccess(context)
            try {
                return openActive(
                    context,
                    StorageSlot(access.activeRoot, access),
                    maintenance ?: access.migration,
                    assetLedgerLogger,
                )
            } catch (failure: Throwable) {
                runCatching { access.close() }.onFailure(failure::addSuppressed)
                throw failure
            }
        }

        private fun openActive(
            context: Context,
            slot: StorageSlot,
            maintenance: MigrationMaintenance,
            assetLedgerLogger: Logger,
        ): MainStorageResources {
            val active = slot.root
            val databases = File(active, "databases")
            check(databases.isDirectory || databases.mkdirs()) { "private database directory unavailable" }
            val database = maintenance.open()
            try {
                val resources = SqlInlineResourceWriter()
                val writer = NoteResourceWriter { transaction, value ->
                    resources.put(
                        transaction,
                        InlineNoteResource(
                            ResourceId.parse(value.resourceId),
                            value.noteId,
                            value.content,
                            value.nowMillis,
                        ),
                    ).value
                }
                val noteRepository = SqlNoteRepository(database, writer, SystemWallClock)
                val files = SqlAttachmentRepository(database, File(active, "vfs_blobs"), SystemWallClock)
                val attachments = AttachmentService(files, AndroidOperationLogger)
                val assetLedgerResources = MainAssetLedgerResources.open(context, active, database, assetLedgerLogger)
                return MainStorageResources(
                    database,
                    MainNotePorts(
                        NotesService(noteRepository, AndroidOperationLogger),
                        SqlNoteTaskStore(database, noteRepository),
                        NoteTaskCancellations(database),
                    ),
                    MainAssetPorts(attachments, MainDocumentTransfers(context, attachments), assetLedgerResources, files),
                    OkHttpNetworkTransport { event ->
                        Log.i(
                            "DstuNetwork",
                            "owner=${event.owner} correlationId=${event.correlationId} " +
                                "state=${event.phase} elapsedMs=${event.elapsedMillis}",
                        )
                    },
                    lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
                        DatabaseRegistry.openShared(
                            File(databases, "mistakes.db"),
                            SharedDatabase.MISTAKES,
                            BundledMigrationSource(context),
                            log = AndroidDatabaseLog,
                            migrationLog = AndroidMigrationLog,
                        )
                    },
                    slot,
                )
            } catch (failure: Exception) {
                database.close()
                throw failure
            }
        }
    }
}

/** The attachment store, its document transfer adapter and asset inventory share VFS. */
private class MainAssetPorts(
    val attachments: AttachmentService,
    val documentTransfers: MainDocumentTransfers,
    private val ledgerResources: MainAssetLedgerResources,
    val workspaceFiles: WorkspaceFiles,
) : AutoCloseable {
    val ledger: AssetLedger get() = ledgerResources.ledger
    val capabilityEpochs get() = ledgerResources.capabilityEpochs
    override fun close() = ledgerResources.close()
}

private class StorageSlot(val root: File, private val owned: AutoCloseable?) : AutoCloseable {
    override fun close() {
        owned?.close()
    }
}

private class MainNotePorts(val notes: Notes, val tasks: SqlNoteTaskStore, val cancellations: NoteTaskCancellations)

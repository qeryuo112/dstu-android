package com.deepstudent.app.storage

import android.content.Context
import com.deepstudent.core.common.id.DeviceId
import com.deepstudent.core.common.logging.Logger
import com.deepstudent.platform.database.BundledMigrationSource
import com.deepstudent.platform.database.DatabaseRegistry
import com.deepstudent.platform.database.SharedDatabase
import com.deepstudent.platform.database.SqlDatabase
import com.deepstudent.services.resources.api.assets.AssetCleanupResult
import com.deepstudent.services.resources.api.assets.AssetDeletionSnapshot
import com.deepstudent.services.resources.api.assets.AssetInventory
import com.deepstudent.services.resources.api.assets.AssetLedger
import com.deepstudent.services.resources.api.assets.AssetReferenceCount
import com.deepstudent.services.resources.api.assets.AssetReferenceOwner
import com.deepstudent.services.resources.api.assets.AssetReferenceReport
import com.deepstudent.services.resources.api.assets.AssetTarget
import com.deepstudent.services.resources.api.assets.AssetTombstoneManifest
import com.deepstudent.services.resources.backend.assets.AssetLedgerService
import com.deepstudent.services.resources.backend.assets.AssetReferenceReader
import com.deepstudent.services.resources.data.assets.ActiveAssetScanner
import com.deepstudent.services.resources.data.assets.OwnedAssetScratch
import com.deepstudent.services.resources.data.assets.SqlAssetDeletionReader
import com.deepstudent.services.resources.data.assets.SqlChatAttachmentReferences
import com.deepstudent.services.resources.data.assets.SqlVfsFilesReferences
import com.deepstudent.services.resources.data.assets.SqlVfsResourceReferences
import java.io.File

/**
 * Owned by the main storage lifecycle. VFS is borrowed and must outlive this owner; its caller
 * closes it. The chat lease is acquired from DatabaseRegistry only on a reference query and is
 * closed here. Closing waits for in-flight operations and rejects subsequent use. Neither scanning
 * nor cleanup opens chat or moves an asset, and no method publishes a sync manifest.
 */
internal class MainAssetLedgerResources private constructor(
    activeRoot: File,
    cacheDir: File,
    vfs: SqlDatabase,
    private val openChatLease: () -> SqlDatabase,
    logger: Logger,
) : AutoCloseable {
    private val lock = Any()
    private var closed = false
    private var chatLease: SqlDatabase? = null

    private val delegate = AssetLedgerService(
        deletions = SqlAssetDeletionReader(vfs),
        readers = listOf(
            SqlVfsFilesReferences(vfs),
            SqlVfsResourceReferences(vfs),
            object : AssetReferenceReader {
                override val owner = AssetReferenceOwner.CHAT_ATTACHMENTS
                override fun inspect(target: AssetTarget): AssetReferenceCount =
                    SqlChatAttachmentReferences(chat()).inspect(target)
            },
        ),
        scratch = OwnedAssetScratch(cacheDir),
        scanner = ActiveAssetScanner(activeRoot),
        logger = logger,
    )

    /** All access goes through the owner so close cannot race a database-backed operation. */
    val ledger: AssetLedger = object : AssetLedger {
        override fun scan(): AssetInventory = withOpen { delegate.scan() }
        override fun references(target: AssetTarget): AssetReferenceReport = withOpen { delegate.references(target) }
        override fun deletionSnapshot(): AssetDeletionSnapshot = withOpen { delegate.deletionSnapshot() }
        override fun deriveReadyTombstones(deviceId: DeviceId): AssetTombstoneManifest =
            withOpen { delegate.deriveReadyTombstones(deviceId) }
        override fun cleanupAsset(target: AssetTarget): AssetCleanupResult = withOpen { delegate.cleanupAsset(target) }
        override fun cleanupBlob(sha256: String): AssetCleanupResult = withOpen { delegate.cleanupBlob(sha256) }
        override fun cleanupOwnScratch(): AssetCleanupResult = withOpen { delegate.cleanupOwnScratch() }
    }

    // Called under lock via ledger.references(); an unsuccessful open is not cached.
    private fun chat(): SqlDatabase = chatLease ?: openChatLease().also { chatLease = it }

    private fun <T> withOpen(block: () -> T): T = synchronized(lock) {
        check(!closed) { "asset ledger owner closed" }
        block()
    }

    override fun close() = synchronized(lock) {
        if (!closed) {
            closed = true
            val lease = chatLease
            chatLease = null
            lease?.close()
        }
    }

    companion object {
        /** Call only from main storage, then close this owner before closing the borrowed VFS. */
        fun open(context: Context, activeRoot: File, vfs: SqlDatabase, logger: Logger): MainAssetLedgerResources {
            val cacheDir = context.cacheDir
            val chatFile = File(activeRoot, "databases/chat_v2.db")
            val source = BundledMigrationSource(context)
            return create(activeRoot, cacheDir, vfs, {
                DatabaseRegistry.openShared(
                    chatFile, SharedDatabase.CHAT_V2, source,
                    log = AndroidDatabaseLog, migrationLog = AndroidMigrationLog,
                )
            }, logger)
        }

        internal fun create(
            activeRoot: File,
            cacheDir: File,
            vfs: SqlDatabase,
            openChatLease: () -> SqlDatabase,
            logger: Logger,
        ): MainAssetLedgerResources = MainAssetLedgerResources(activeRoot, cacheDir, vfs, openChatLease, logger)
    }
}

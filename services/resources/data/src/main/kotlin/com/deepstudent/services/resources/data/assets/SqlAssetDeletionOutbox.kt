package com.deepstudent.services.resources.data.assets

import com.deepstudent.platform.database.SqlDatabase
import com.deepstudent.services.resources.api.assets.*
import com.deepstudent.services.resources.backend.assets.AssetDeletionReader
import java.util.Locale

/** Reads frozen VFS journal and ready-only outbox in one snapshot; neither table is changed. */
class SqlAssetDeletionReader(private val database: SqlDatabase) : AssetDeletionReader, AssetDeletionOutbox {
    override fun read(): AssetDeletionSnapshot = database.readSnapshot {
        val ready = query("SELECT key,size,deleted_at,retry_count FROM __asset_deletion_queue ORDER BY deleted_at,key") {
            QueuedAssetTombstone(it.text(0), if (it.isNull(1)) null else it.long(1), it.text(2), it.long(3))
        }
        val journal = query("""SELECT operation_id,entity_key,state FROM __file_deletion_journal
            WHERE target_kind='asset' ORDER BY prepared_at,operation_id""".trimIndent()) {
            AssetJournalEntry(it.text(0), it.text(1), AssetJournalState.valueOf(it.text(2).uppercase(Locale.ROOT)))
        }
        AssetDeletionSnapshot(ready, journal)
    }

    override fun readReady(): List<QueuedAssetTombstone> = read().ready
}

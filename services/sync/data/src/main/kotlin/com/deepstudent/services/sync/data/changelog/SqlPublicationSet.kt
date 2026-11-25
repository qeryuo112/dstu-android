package com.deepstudent.services.sync.data.changelog

import com.deepstudent.services.sync.api.DomainSyncAdapter
import com.deepstudent.services.sync.api.SyncChangeReceipt
import com.deepstudent.services.sync.api.SyncError
import com.deepstudent.services.sync.backend.SyncFailure

/** A run may finish its acknowledgements across separate transactions after restarting. */
class SqlPublicationSet(
    private val sources: List<SqlPublicationSource>,
    private val incoming: DomainSyncAdapter,
    private val readPending: (SqlPublicationSource) -> List<ByteArray> = { it.pending() },
) : DomainSyncAdapter by incoming {
    private val byScope = sources.associateBy { it.scope.id }
    init {
        require(sources.size == byScope.size)
    }

    override fun pending(): List<ByteArray> {
        val collected = BoundedPublicationRows()
        sources.forEach { source -> readPending(source).forEach(collected::add) }
        return collected.rows.toList()
    }

    override fun markPublished(changeLogIds: List<Long>, version: Long) =
        markPublishedReceipts(changeLogIds.map { SyncChangeReceipt(SyncChangeReceipt.VFS, it) }, version)

    override fun markPublishedReceipts(receipts: List<SyncChangeReceipt>, version: Long) {
        val grouped = receipts.groupBy(SyncChangeReceipt::database)
        if (!byScope.keys.containsAll(grouped.keys)) throw SyncFailure(SyncError.STORAGE)
        for ((database, rows) in grouped) byScope.getValue(database).acknowledge(rows.map(SyncChangeReceipt::changeLogId), version)
    }
}

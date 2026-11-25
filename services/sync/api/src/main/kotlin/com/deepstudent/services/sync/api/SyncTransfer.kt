package com.deepstudent.services.sync.api

/** A SQLite change log id is only unique inside its owning database. */
data class SyncChangeReceipt(val database: String, val changeLogId: Long) {
    init {
        require(database in DATABASES)
    }

    companion object {
        const val VFS = "vfs"
        private val DATABASES = setOf(VFS, "chat_v2", "mistakes", "llm_usage")
    }
}

interface DomainSyncAdapter {
    fun pending(): List<ByteArray>
    fun markPublished(changeLogIds: List<Long>, version: Long)
    fun markPublishedReceipts(receipts: List<SyncChangeReceipt>, version: Long) {
        require(receipts.all { it.database == SyncChangeReceipt.VFS })
        markPublished(receipts.map(SyncChangeReceipt::changeLogId), version)
    }
    fun cursor(device: String): Long
    fun apply(device: String, seq: Long, changes: List<ByteArray>)

    /** Carries the pipeline's session correlation through the downloaded batch. */
    fun applyWithCorrelation(device: String, seq: Long, changes: List<ByteArray>, correlationId: String) = apply(device, seq, changes)
}

enum class SyncTransferPhase { IDLE, BLOBS, DOWNLOAD, UPLOAD, COMPLETE, FAILED }

data class SyncTransferState(val phase: SyncTransferPhase, val correlationId: String? = null, val error: SyncError? = null)

interface SyncTransfer {
    val state: kotlinx.coroutines.flow.StateFlow<SyncTransferState>
    suspend fun synchronize(): SyncResult<Unit>
}

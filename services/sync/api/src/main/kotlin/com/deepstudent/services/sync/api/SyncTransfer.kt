package com.deepstudent.services.sync.api

interface DomainSyncAdapter {
    fun pending(): List<ByteArray>
    fun markPublished(changeLogIds: List<Long>, version: Long)
    fun cursor(device: String): Long
    fun apply(device: String, seq: Long, changes: List<ByteArray>)
}

enum class SyncTransferPhase { IDLE, BLOBS, DOWNLOAD, UPLOAD, COMPLETE, FAILED }

data class SyncTransferState(val phase: SyncTransferPhase, val correlationId: String? = null, val error: SyncError? = null)

interface SyncTransfer {
    val state: kotlinx.coroutines.flow.StateFlow<SyncTransferState>
    suspend fun synchronize(): SyncResult<Unit>
}

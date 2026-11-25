package com.deepstudent.services.sync.backend.transfer

import com.deepstudent.core.common.logging.Logger
import com.deepstudent.core.common.logging.NoopLogger
import com.deepstudent.services.sync.api.SyncChangeReceipt

class PublicationOptions(val seed: RepositorySeed? = null, val logger: Logger = NoopLogger)

/** Separate full-inventory input. A pending log fragment is never a seed. */
fun interface RepositorySeed {
    fun snapshot(): SeedPublication
}

data class SeedPublication(val changes: List<ByteArray>, val receipts: List<SyncChangeReceipt>)

class PendingPublication(
    val key: String,
    val sequence: Long,
    val version: Long,
    val payload: ByteArray,
    val receipts: List<SyncChangeReceipt>,
    val seed: Boolean = false,
) {
    override fun toString(): String = "PendingPublication(sequence=$sequence,bytes=${payload.size})"
}

interface PublicationJournal : SyncUploadState {
    fun pending(): PendingPublication?
    fun reserve(publication: PendingPublication)
    fun complete(publication: PendingPublication)
}

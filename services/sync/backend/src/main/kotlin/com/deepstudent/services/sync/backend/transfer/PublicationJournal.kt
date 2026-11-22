package com.deepstudent.services.sync.backend.transfer

class PendingPublication(
    val key: String,
    val sequence: Long,
    val version: Long,
    val payload: ByteArray,
    val changeLogIds: List<Long>,
) {
    override fun toString(): String = "PendingPublication(sequence=$sequence,bytes=${payload.size})"
}

interface PublicationJournal : SyncUploadState {
    fun pending(): PendingPublication?
    fun reserve(publication: PendingPublication)
    fun complete(publication: PendingPublication)
}

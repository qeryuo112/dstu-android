package com.deepstudent.services.sync.backend.transfer

interface SyncCompression {
    fun encode(bytes: ByteArray): ByteArray
    fun decode(bytes: ByteArray): ByteArray
}

data class SyncBlob(val hash: String, val relativePath: String, val size: Long, val updatedAt: String)

interface SyncBlobs {
    fun list(): List<SyncBlob>
    fun read(blob: SyncBlob): ByteArray?
    fun install(blob: SyncBlob, bytes: ByteArray)
}

interface SyncUploadState {
    fun published(): Long
    fun markPublished(seq: Long)
}

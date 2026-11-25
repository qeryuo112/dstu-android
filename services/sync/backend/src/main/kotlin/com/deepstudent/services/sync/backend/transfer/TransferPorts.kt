package com.deepstudent.services.sync.backend.transfer

import java.io.InputStream
import java.io.OutputStream

interface SyncCompression {
    fun encode(bytes: ByteArray): ByteArray
    fun decode(bytes: ByteArray): ByteArray
}

data class SyncBlob(val hash: String, val relativePath: String, val size: Long, val updatedAt: String)

interface SyncBlobs {
    fun list(): List<SyncBlob>
    fun read(blob: SyncBlob): ByteArray?
    fun install(blob: SyncBlob, bytes: ByteArray)

    fun copyTo(blob: SyncBlob, output: OutputStream, check: () -> Unit): Boolean {
        val bytes = read(blob) ?: return false
        check()
        output.write(bytes)
        return true
    }

    fun installFrom(blob: SyncBlob, input: InputStream, check: () -> Unit) {
        val bytes = ByteArray(blob.size.toInt())
        java.io.DataInputStream(input).readFully(bytes)
        check()
        install(blob, bytes)
    }
}

interface SyncUploadState {
    fun published(): Long
    fun markPublished(seq: Long)
}

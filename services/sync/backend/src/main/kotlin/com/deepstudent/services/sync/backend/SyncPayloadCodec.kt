package com.deepstudent.services.sync.backend

import com.deepstudent.services.sync.api.SyncError
import java.io.InputStream
import java.io.OutputStream

object SyncObjectLimits {
    const val PAYLOAD_BYTES = 16 * 1024 * 1024

    // 44-byte DSBK header and up to 16 tags for our 1 MiB chunks.
    const val WIRE_BYTES = PAYLOAD_BYTES + 44 + 16 * 16

    fun requirePayload(bytes: ByteArray) {
        if (bytes.size > PAYLOAD_BYTES) throw SyncFailure(SyncError.LIMIT_EXCEEDED)
    }
}

interface SyncPayloadCodec : AutoCloseable {
    val encrypted: Boolean
    fun encode(bytes: ByteArray, blob: Boolean = false): ByteArray
    fun decode(bytes: ByteArray, blob: Boolean = false): ByteArray
    override fun close() = Unit

    /** Blob stream methods write only to a private stage; never to a published path. */
    fun encodeBlob(input: InputStream, output: OutputStream) {
        if (encrypted) throw SyncFailure(SyncError.INCOMPATIBLE_FORMAT)
        input.copyTo(output, 64 * 1024)
    }
    fun decodeBlob(input: InputStream, output: OutputStream) = encodeBlob(input, output)
}

object PlainSyncPayload : SyncPayloadCodec {
    override val encrypted = false
    override fun encode(bytes: ByteArray, blob: Boolean): ByteArray = bytes.also(SyncObjectLimits::requirePayload)
    override fun decode(bytes: ByteArray, blob: Boolean): ByteArray {
        SyncObjectLimits.requirePayload(bytes)
        // A plain attachment may itself be a backup file. Its manifest and plain hash bind those bytes.
        if (!blob && hasDsbkMagic(bytes)) throw SyncFailure(SyncError.ENCRYPTED_REPOSITORY)
        return bytes
    }
}

internal fun hasDsbkMagic(bytes: ByteArray): Boolean = bytes.size >= 4 && bytes[0] == 68.toByte() && bytes[1] == 83.toByte() &&
    bytes[2] == 66.toByte() && bytes[3] == 75.toByte()

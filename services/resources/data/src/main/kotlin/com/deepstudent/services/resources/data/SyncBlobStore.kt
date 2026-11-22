package com.deepstudent.services.resources.data

import com.deepstudent.core.common.id.Digests
import java.io.File

class SyncBlobStore(root: File) {
    private val files = BlobFiles(root)

    fun exists(hash: String, relativePath: String): Boolean = files.exists(relativePath, hash)

    fun read(hash: String, relativePath: String, size: Long): ByteArray? {
        val bytes = files.read(relativePath, hash) ?: return null
        verify(hash, size, bytes)
        return bytes
    }

    fun install(hash: String, relativePath: String, size: Long, bytes: ByteArray) {
        verify(hash, size, bytes)
        files.put(hash, bytes, relativePath)
    }

    private fun verify(hash: String, size: Long, bytes: ByteArray) {
        if (size != bytes.size.toLong() || Digests.sha256Hex(bytes) != hash) corruptAttachment()
    }
}

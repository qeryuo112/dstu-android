package com.deepstudent.services.resources.data

import com.deepstudent.core.common.id.Digests
import java.io.File
import java.io.InputStream
import java.io.OutputStream

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

    fun copyVerified(hash: String, path: String, size: Long, output: OutputStream, check: () -> Unit): Boolean =
        files.copy(path, hash, size, output, check, verify = true)

    fun verifyPresent(hash: String, path: String, size: Long, check: () -> Unit): Boolean =
        copyVerified(hash, path, size, DiscardBlobBytes, check)

    fun installVerified(hash: String, path: String, size: Long, input: InputStream, check: () -> Unit) {
        files.putVerified(hash, input, size, path, check)
    }

    private fun verify(hash: String, size: Long, bytes: ByteArray) {
        if (size != bytes.size.toLong() || Digests.sha256Hex(bytes) != hash) corruptAttachment()
    }
}

private object DiscardBlobBytes : OutputStream() {
    override fun write(b: Int) = Unit
    override fun write(b: ByteArray, off: Int, len: Int) = Unit
}

package com.deepstudent.services.resources.data

import com.deepstudent.core.common.error.DstuException
import com.deepstudent.core.common.id.Digests
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class SyncBlobStoreTest {
    private val root = Files.createTempDirectory("sync-blobs").toFile()
    private val blobs = root.resolve("vfs_blobs")
    private val bytes = ByteArray(8192) { (it % 251).toByte() }
    private val hash = Digests.sha256Hex(bytes)
    private val path = "${hash.take(2)}/$hash.bin"

    @After fun cleanup() { root.deleteRecursively() }

    @Test fun installReadAndReopen() {
        assertNull(SyncBlobStore(blobs).read(hash, path, bytes.size.toLong()))
        SyncBlobStore(blobs).install(hash, path, bytes.size.toLong(), bytes)
        SyncBlobStore(blobs).install(hash, path, bytes.size.toLong(), bytes)
        assertArrayEquals(bytes, SyncBlobStore(blobs).read(hash, path, bytes.size.toLong()))
    }

    @Test fun rejectsBadBytesSizePathsAndTampering() {
        val store = SyncBlobStore(blobs)
        assertThrows(DstuException::class.java) { store.install(hash, path, bytes.size.toLong(), ByteArray(bytes.size)) }
        assertThrows(DstuException::class.java) { store.install(hash, path, 1, bytes) }
        assertThrows(DstuException::class.java) { store.install(hash, "../$hash.bin", bytes.size.toLong(), bytes) }
        store.install(hash, path, bytes.size.toLong(), bytes)
        blobs.resolve(path).writeBytes(ByteArray(bytes.size))
        assertThrows(DstuException::class.java) { store.read(hash, path, bytes.size.toLong()) }
    }
}

package com.deepstudent.services.sync.backend

import com.deepstudent.services.sync.api.SyncError
import com.deepstudent.services.sync.backend.transfer.BlobTransfer
import com.deepstudent.services.sync.backend.transfer.SyncBlob
import com.deepstudent.services.sync.backend.transfer.SyncBlobs
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BlobTransferTest {
    private val bytes = "attachment body".toByteArray()
    private val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private val path = "${hash.take(2)}/$hash.bin"
    private fun blob(time: String = "2026-01-01T00:00:00Z") = SyncBlob(hash, path, bytes.size.toLong(), time)
    private fun manifest(time: String = "2026-01-01T00:00:00Z", relative: String = path) =
        """{"entries":{"$hash":{"relative_path":"$relative","size":${bytes.size},"updated_at":"$time"}},"updated_at":""}""".toByteArray()
    private fun tombstone(time: String) = """{"entries":{"$hash":{"deleted_at":"$time","device_id":"peer"}},"updated_at":""}""".toByteArray()

    private class Provider : ObjectProvider {
        val objects = mutableMapOf<String, ByteArray>()
        val puts = mutableListOf<String>()
        val missing = mutableListOf<String>()
        var badReadback = false
        var cancelOnPut = false
        override suspend fun get(key: String): ByteArray? = objects[key]?.let {
            if (badReadback && key.startsWith("data_governance/blobs/")) byteArrayOf(0) else it
        }
        override suspend fun put(key: String, value: ByteArray, ifAbsent: Boolean) {
            if (cancelOnPut) throw CancellationException()
            puts.add(key)
            objects[key] = value.copyOf()
        }
        override suspend fun list(prefix: String) = (objects.keys + missing).filter { it.startsWith(prefix) }
        override suspend fun delete(key: String) { objects.remove(key) }
    }
    private class Blobs(var items: List<SyncBlob> = emptyList(), var content: ByteArray? = null) : SyncBlobs {
        var installs = 0
        override fun list() = items
        override fun read(blob: SyncBlob) = content
        override fun install(blob: SyncBlob, bytes: ByteArray) { installs++; items = listOf(blob); content = bytes.copyOf() }
    }

    @Test fun downloadAndRepeatDoNotUpload() = runBlocking {
        val provider = Provider()
        provider.objects["data_governance/blobs_manifest.json"] = manifest()
        provider.objects["data_governance/blobs/$path"] = bytes
        val local = Blobs()
        val transfer = BlobTransfer(provider, local)
        transfer.synchronize()
        transfer.synchronize()
        assertArrayEquals(bytes, local.content)
        assertEquals(1, local.installs)
        assertTrue(provider.puts.isEmpty())
    }

    @Test fun tombstoneSuppressesUploadUntilStrictlyLaterRestore() = runBlocking {
        val provider = Provider()
        provider.objects["data_governance/tombstones/blobs.json"] = tombstone("2026-01-02T00:00:00Z")
        val local = Blobs(listOf(blob("2026-01-02T00:00:00Z")), bytes)
        BlobTransfer(provider, local).synchronize()
        assertTrue(provider.puts.isEmpty())
        local.items = listOf(blob("2026-01-02T00:00:00.001Z"))
        BlobTransfer(provider, local).synchronize()
        assertEquals(2, provider.puts.size)
        BlobTransfer(provider, local).synchronize()
        assertEquals(2, provider.puts.size)
    }

    @Test fun readbackFailurePreventsManifestPublication() {
        val provider = Provider().apply { badReadback = true }
        val error = assertThrows(SyncFailure::class.java) {
            runBlocking { BlobTransfer(provider, Blobs(listOf(blob()), bytes)).synchronize() }
        }
        assertEquals(SyncError.NETWORK, error.error)
        assertEquals(listOf("data_governance/blobs/$path"), provider.puts)
    }

    @Test fun badPathMissingObjectAndHashMismatchFailBeforeInstall() {
        for (mode in 0..2) {
            val provider = Provider()
            provider.objects["data_governance/blobs_manifest.json"] = if (mode == 0) manifest(relative = "../$hash.bin") else manifest()
            if (mode == 2) provider.objects["data_governance/blobs/$path"] = ByteArray(bytes.size)
            val local = Blobs()
            assertThrows(SyncFailure::class.java) { runBlocking { BlobTransfer(provider, local).synchronize() } }
            assertEquals(0, local.installs)
        }
    }

    @Test fun listedMissingAndUnsupportedEventsFailClosed() {
        val provider = Provider()
        provider.missing.add("data_governance/file_manifests/blobs/missing.json")
        assertThrows(SyncFailure::class.java) { runBlocking { BlobTransfer(provider, Blobs()).synchronize() } }
        provider.missing.clear()
        provider.objects["data_governance/tombstone-events/blobs/peer/0001-a.json"] = byteArrayOf(1)
        val failure = assertThrows(SyncFailure::class.java) { runBlocking { BlobTransfer(provider, Blobs()).synchronize() } }
        assertEquals(SyncError.INCOMPATIBLE_FORMAT, failure.error)
    }

    @Test fun appendTombstonesAndManifestTiesUseFirstEntry() = runBlocking {
        val provider = Provider()
        val time = "2026-01-02T00:00:00Z"
        provider.objects["data_governance/tombstones/blobs/peer.json"] = tombstone(time)
        provider.objects["data_governance/file_manifests/blobs/a.json"] = manifest(time)
        provider.objects["data_governance/file_manifests/blobs/b.json"] = manifest(time, "${hash.take(2)}/$hash.jpg")
        val local = Blobs(listOf(blob(time)), bytes)
        BlobTransfer(provider, local).synchronize()
        assertTrue(provider.puts.isEmpty())
        assertEquals(0, local.installs)
        provider.objects.remove("data_governance/tombstones/blobs/peer.json")
        provider.objects["data_governance/blobs/$path"] = bytes
        val empty = Blobs()
        BlobTransfer(provider, empty).synchronize()
        assertEquals(path, empty.items.single().relativePath)
    }

    @Test fun oversizedAndEncryptedAreRejected() {
        val provider = Provider()
        val oversized = manifest().decodeToString().replace("\"size\":${bytes.size}", "\"size\":16777217")
        provider.objects["data_governance/blobs_manifest.json"] = oversized.toByteArray()
        val failure = assertThrows(SyncFailure::class.java) { runBlocking { BlobTransfer(provider, Blobs()).synchronize() } }
        assertEquals(SyncError.LIMIT_EXCEEDED, failure.error)
        provider.objects["data_governance/blobs_manifest.json"] = "DSBKprivate".toByteArray()
        val encrypted = assertThrows(SyncFailure::class.java) { runBlocking { BlobTransfer(provider, Blobs()).synchronize() } }
        assertEquals(SyncError.ENCRYPTED_REPOSITORY, encrypted.error)
    }

    @Test fun cancellationNeverPublishesManifest() {
        val provider = Provider().apply { cancelOnPut = true }
        assertThrows(CancellationException::class.java) {
            runBlocking { BlobTransfer(provider, Blobs(listOf(blob()), bytes)).synchronize() }
        }
        assertTrue(provider.puts.isEmpty())
    }
}

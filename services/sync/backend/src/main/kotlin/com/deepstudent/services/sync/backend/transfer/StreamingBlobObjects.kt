package com.deepstudent.services.sync.backend.transfer

import com.deepstudent.core.common.id.Digests
import com.deepstudent.services.sync.api.SyncError
import com.deepstudent.services.sync.backend.BoundedObjectInput
import com.deepstudent.services.sync.backend.ObjectProvider
import com.deepstudent.services.sync.backend.SyncFailure
import com.deepstudent.services.sync.backend.SyncObjectLimits
import com.deepstudent.services.sync.backend.SyncPayloadCodec
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

/** Never gives the resource store an unverified stream or an unpublished plaintext path. */
internal class StreamingBlobObjects(
    private val provider: ObjectProvider,
    private val blobs: SyncBlobs,
    private val codec: SyncPayloadCodec,
    private val directory: File,
) {
    suspend fun localAvailable(blob: SyncBlob): Boolean {
        val context = currentCoroutineContext()
        return blobs.copyTo(blob, DiscardBytes) { context.ensureActive() }
    }

    suspend fun download(key: String, entry: BlobManifestEntry) = stage { wire ->
        val context = currentCoroutineContext()
        val observed = fetch(key, wire) ?: blobFailure(SyncError.NOT_FOUND)
        if (entry.cipher != null && entry.cipher != observed.cipher()) blobFailure(SyncError.ENCRYPTED_DATA_INVALID)
        readPlain(wire, entry.blob) { verified ->
            context.ensureActive()
            verified.inputStream().use { blobs.installFrom(entry.blob, it) { context.ensureActive() } }
        }
    }

    suspend fun upload(key: String, blob: SyncBlob): CipherBlob? = stage { plain ->
        val context = currentCoroutineContext()
        val copied = plain.outputStream().use { blobs.copyTo(blob, it) { context.ensureActive() } }
        if (!copied) blobFailure(SyncError.NOT_FOUND)
        verify(plain, blob)
        if (!codec.encrypted) {
            publish(key, blob, plain)
        } else {
            stage { wire ->
                bounded(plain, BLOB_LIMIT) { input -> wire.outputStream().use { codec.encodeBlob(input, it) } }
                publish(key, blob, wire)
            }
        }
    }

    private suspend fun publish(key: String, blob: SyncBlob, body: File): CipherBlob? = stage { observed ->
        val existing = fetch(key, observed)
        val fingerprint = existing ?: run {
            try {
                provider.putStream(key, body.length(), true, body::inputStream)
            } catch (
                failure: SyncFailure,
            ) {
                if (failure.error != SyncError.ALREADY_EXISTS) throw failure
            }
            fetch(key, observed) ?: blobFailure(SyncError.NETWORK)
        }
        readPlain(observed, blob) { }
        if (codec.encrypted) fingerprint.cipher() else null
    }

    private suspend fun fetch(key: String, target: File): Fingerprint? {
        val context = currentCoroutineContext()
        return provider.readStream(key, SyncObjectLimits.WIRE_BYTES) { raw ->
            val input = BoundedObjectInput(raw, SyncObjectLimits.WIRE_BYTES.toLong()) { context.ensureActive() }
            context.ensureActive()
            // Async HTTP callbacks must not recreate a file already removed by cancellation.
            Files.newOutputStream(target.toPath(), StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use {
                input.copyTo(it, TRANSFER_BUFFER)
            }
        }?.let { fingerprint(target, SyncObjectLimits.WIRE_BYTES.toLong()) }
    }

    private suspend fun readPlain(wire: File, blob: SyncBlob, use: (File) -> Unit) {
        if (!codec.encrypted) {
            verify(wire, blob)
            use(wire)
        } else {
            stage { plain ->
                bounded(wire, SyncObjectLimits.WIRE_BYTES.toLong()) { input ->
                    plain.outputStream().use { codec.decodeBlob(input, it) }
                }
                verify(plain, blob)
                use(plain)
            }
        }
    }

    private suspend fun verify(file: File, blob: SyncBlob) {
        val check = fingerprint(file, BLOB_LIMIT)
        if (check.size != blob.size || check.sha256 != blob.hash) blobFailure()
    }

    private suspend fun fingerprint(file: File, limit: Long): Fingerprint = bounded(file, limit) { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val size = java.security.DigestInputStream(input, digest).copyTo(DiscardBytes, TRANSFER_BUFFER)
        Fingerprint(Digests.toHex(digest.digest()), size)
    }

    private suspend fun <T> bounded(file: File, limit: Long, read: (InputStream) -> T): T {
        val context = currentCoroutineContext()
        return BoundedObjectInput(file.inputStream(), limit) { context.ensureActive() }.use(read)
    }

    private suspend fun <T> stage(block: suspend (File) -> T): T {
        check(directory.isDirectory || directory.mkdirs())
        val path = Files.createTempFile(directory.toPath(), "sync-object-", ".stage")
        try {
            return block(path.toFile())
        } finally {
            Files.deleteIfExists(path)
        }
    }

    private data class Fingerprint(val sha256: String, val size: Long) {
        fun cipher() = CipherBlob(sha256, size)
    }
}

private const val TRANSFER_BUFFER = 64 * 1024
private object DiscardBytes : OutputStream() {
    override fun write(b: Int) = Unit
    override fun write(b: ByteArray, off: Int, len: Int) = Unit
}

package com.deepstudent.services.resources.data

import com.deepstudent.core.common.error.DstuError
import com.deepstudent.core.common.error.DstuException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID

/** Private VFS blobs; final paths remain the frozen hash-prefix/hash.extension layout. */
internal class BlobFiles(
    root: File,
    private val faults: ResourceFaults = ResourceFaults.NONE,
    private val space: AvailableSpace = AvailableSpace.REAL,
    private val identities: BlobIdentityReader = BlobIdentityReader.REAL,
) {
    private val root: Path = root.toPath().toAbsolutePath().normalize()

    /** All blob publishers, including sync, share this process and filesystem lock. */
    fun <T> locked(block: () -> T): T = synchronized(publicationLock) {
        fileOperation(FileStage.ROOT) { ensureRoot() }
        fileOperation(FileStage.PUBLISH) {
            FileChannel.open(root.resolve(".publication.lock"), StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, NOFOLLOW_LINKS).use { channel ->
                channel.lock().use { block() }
            }
        }
    }

    fun prepare(
        input: InputStream, expectedSize: Long?, maxSize: Long,
        check: () -> Unit = {}, progress: (Long) -> Unit = {},
    ): PreparedBlob {
        if (expectedSize != null && (expectedSize < 0 || expectedSize > maxSize)) invalidSize()
        val prepared = createTemporary()
        try {
            fileOperation(FileStage.SPACE) { checkSpace(expectedSize ?: 0L) }
            fillPrepared(prepared, input, expectedSize, maxSize, check, progress)
            return prepared
        } catch (failure: Throwable) {
            try { prepared.close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }

    private fun createTemporary(): PreparedBlob = locked {
        val path = root.resolve(".prepare-${UUID.randomUUID()}.tmp")
        fileOperation(FileStage.TEMP_WRITE) {
            val channel = FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                StandardOpenOption.READ, NOFOLLOW_LINKS)
            try { PreparedBlob(path, channel, channel.lock(), faults).also { livePrepared.add(path) } }
            catch (failure: Throwable) { channel.close(); Files.deleteIfExists(path); throw failure }
        }
    }

    private fun fillPrepared(
        prepared: PreparedBlob, input: InputStream, expected: Long?, max: Long,
        check: () -> Unit, progress: (Long) -> Unit,
    ) {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_SIZE)
        var total = 0L
        check(); progress(0L)
        while (true) {
            check(); faults.hit(ResourceCut.PREPARE_READ, total)
            val count = fileOperation(FileStage.SOURCE_READ) { input.read(buffer) }
            if (count < 0) break
            if (count == 0) continue
            checkWriteSize(total, count, max, expected)
            fileOperation(FileStage.SPACE) { checkSpace(count.toLong()) }
            digest.update(buffer, 0, count)
            writeChunk(prepared, buffer, count, total, check)
            total += count
            progress(total)
        }
        check()
        if (expected != null && expected != total) invalidSize()
        fileOperation(FileStage.TEMP_WRITE) { prepared.channel.force(true) }
        prepared.size = total
        prepared.hash = digest.hex()
    }

    private fun checkWriteSize(total: Long, count: Int, max: Long, expected: Long?) {
        if (total > max - count || (expected != null && total > expected - count)) invalidSize()
    }

    private fun writeChunk(prepared: PreparedBlob, data: ByteArray, count: Int, total: Long, check: () -> Unit) {
        val bytes = ByteBuffer.wrap(data, 0, count)
        fileOperation(FileStage.TEMP_WRITE) {
            while (bytes.hasRemaining()) {
                check()
                val written = prepared.channel.write(bytes)
                if (written == 0) continue
                faults.hit(ResourceCut.PREPARE_WRITE, total + bytes.position())
            }
        }
    }

    /** Must hold [locked] through the DB commit and subsequent settlement. */
    fun publish(prepared: PreparedBlob, previousPath: String? = null): String {
        val hash = prepared.hash
        val relative = previousPath ?: "${hash.take(2)}/$hash.bin"
        val target = fileOperation(FileStage.RESOLVE) { resolve(relative, hash) }
        fileOperation(FileStage.DIRECTORY) { ensureDirectory(target.parent) }
        faults.hit(ResourceCut.BEFORE_PUBLISH, prepared.size)
        fileOperation(FileStage.PUBLISH) {
            if (Files.exists(target, NOFOLLOW_LINKS)) {
                if (!matchesBlob(target, hash, prepared.size)) corruptAttachment()
            } else {
                publishNew(prepared, target, relative)
            }
        }
        return relative
    }

    private fun publishNew(prepared: PreparedBlob, target: Path, relative: String) {
        val before = identities.read(prepared.path) ?: ownershipFailure()
        val ext = relative.substringAfterLast('.')
        val marker = root.resolve(".pending-${prepared.hash}-$ext-${UUID.randomUUID()}.meta")
        BlobOwnership.create(marker, before)
        prepared.marker = marker
        faults.hit(ResourceCut.AFTER_MARKER, prepared.size)
        Files.move(prepared.path, target, ATOMIC_MOVE)
        prepared.published = true
        faults.hit(ResourceCut.AFTER_MOVE, prepared.size)
        val after = identities.read(target) ?: ownershipFailure()
        if (!before.sameMovedFile(after)) ownershipFailure()
        BlobOwnership.recordPublication(marker, after)
        faults.hit(ResourceCut.AFTER_PUBLISH, prepared.size)
    }

    /** Must hold [locked] and a DB writer transaction while checking persistent ownership. */
    fun settle(prepared: PreparedBlob, hasOwner: (String, String) -> Boolean): Boolean {
        val marker = prepared.marker ?: return true
        val settled = fileOperation(FileStage.CLEANUP) { settleMarker(marker, hasOwner) }
        if (settled) prepared.marker = null
        return settled
    }

    /** Recover abandoned private names. Active writers retain a lock on their inode. */
    fun recover(hasOwner: (String, String) -> Boolean): Int = fileOperation(FileStage.CLEANUP) {
        var uncertain = 0
        Files.newDirectoryStream(root).use { entries ->
            for (path in entries) {
                val name = path.fileName.toString()
                if (PREPARE.matches(name)) recoverPrepare(path)
                if (PENDING.matches(name) && !settleMarker(path, hasOwner)) uncertain++
                if (LEGACY_PENDING.matches(name)) recoverLegacyMarker(path)
            }
        }
        uncertain
    }

    private fun recoverPrepare(path: Path) {
        if (path in livePrepared || !Files.isRegularFile(path, NOFOLLOW_LINKS)) return
        withUnlockedFile(path) { Files.deleteIfExists(path) }
    }

    // Old hard-link markers are never proof that a final path is safe to remove.
    private fun recoverLegacyMarker(path: Path) {
        if (path in livePrepared || !Files.isRegularFile(path, NOFOLLOW_LINKS)) return
        withUnlockedFile(path) { Files.deleteIfExists(path) }
    }

    private fun settleMarker(marker: Path, hasOwner: (String, String) -> Boolean): Boolean {
        if (!Files.isRegularFile(marker, NOFOLLOW_LINKS)) return false
        val target = targetForMarker(marker) ?: return false
        val quarantine = quarantineForMarker(marker) ?: return false
        if (Files.exists(quarantine, NOFOLLOW_LINKS)) return false
        if (!Files.exists(target, NOFOLLOW_LINKS)) {
            Files.deleteIfExists(marker)
            return true
        }
        if (hasOwner(markerHash(marker), root.relativize(target).toString())) {
            Files.deleteIfExists(marker)
            return true
        }
        return settleUnowned(marker, target, quarantine)
    }

    private fun settleUnowned(marker: Path, target: Path, quarantine: Path): Boolean {
        val published = BlobOwnership.read(marker)?.published ?: return false
        val current = identities.read(target) ?: return false
        if (published != current) {
            // A different file appeared. Forget the claim, never unlink the replacement.
            Files.deleteIfExists(marker)
            return true
        }
        if (!reapOwned(target, quarantine, published, identities, faults)) return false
        Files.deleteIfExists(marker)
        return true
    }

    /** Sync installs are themselves a durable claim even if their DB row arrives later. */
    private fun adoptAbandoned(target: Path) {
        Files.newDirectoryStream(root, ".pending-*.meta").use { entries ->
            for (path in entries) {
                if (!PENDING.matches(path.fileName.toString()) || !Files.isRegularFile(path, NOFOLLOW_LINKS)) continue
                if (targetForMarker(path) == target && !Files.exists(quarantineForMarker(path), NOFOLLOW_LINKS)) {
                    Files.deleteIfExists(path)
                }
            }
        }
    }

    fun put(hash: String, bytes: ByteArray, previousPath: String? = null): String {
        val prepared = prepare(ByteArrayInputStream(bytes), bytes.size.toLong(), MAX_BYTES)
        try {
            if (prepared.hash != hash) corruptAttachment()
            return locked {
                val relative = publish(prepared, previousPath)
                fileOperation(FileStage.CLEANUP) {
                    // Remove our marker while holding the publication lock: sync is now the owner.
                    prepared.marker?.let { Files.deleteIfExists(it); prepared.marker = null }
                    adoptAbandoned(resolve(relative, hash))
                }
                relative
            }
        } finally { prepared.close() }
    }

    fun read(relative: String, hash: String): ByteArray? {
        val output = ByteArrayOutputStream()
        if (!copy(relative, hash, null, output)) return null
        return output.toByteArray()
    }

    /** Returns false for a missing blob; validates bounded size/hash before reporting success. */
    fun copy(
        relative: String, hash: String, expectedSize: Long?, output: OutputStream,
        check: () -> Unit = {}, progress: (Long) -> Unit = {}, verify: Boolean = false,
    ): Boolean = fileOperation(FileStage.READ) {
        val path = readablePath(relative, hash) ?: return false
        copyFrom(path, hash, expectedSize, output, check, progress, verify)
        true
    }

    fun exists(relative: String, hash: String): Boolean = fileOperation(FileStage.READ) { readablePath(relative, hash) != null }

    private fun readablePath(relative: String, hash: String): Path? {
        if (Files.notExists(root, NOFOLLOW_LINKS)) return null
        ensureRoot()
        val path = resolve(relative, hash)
        if (Files.notExists(path, NOFOLLOW_LINKS)) return null
        if (!Files.isRegularFile(path, NOFOLLOW_LINKS) || Files.size(path) > MAX_BYTES) corruptAttachment()
        return path
    }

    private fun copyFrom(
        path: Path, hash: String, expected: Long?, output: OutputStream,
        check: () -> Unit, progress: (Long) -> Unit, verify: Boolean,
    ) {
        val digest = if (verify) MessageDigest.getInstance("SHA-256") else null
        check(); progress(0L)
        val total = Files.newInputStream(path, StandardOpenOption.READ, NOFOLLOW_LINKS).use { input ->
            transfer(input, output, expected, digest, check, progress)
        }
        check()
        if (expected != null && total != expected) corruptAttachment()
        if (digest != null && digest.hex() != hash) corruptAttachment()
    }

    private fun transfer(
        input: InputStream, output: OutputStream, expected: Long?, digest: MessageDigest?,
        check: () -> Unit, progress: (Long) -> Unit,
    ): Long {
        var total = 0L
        val bytes = ByteArray(BUFFER_SIZE)
        while (true) {
            check()
            val count = input.read(bytes)
            if (count < 0) break
            if (count == 0) continue
            total += count
            if (total > MAX_BYTES || (expected != null && total > expected)) corruptAttachment()
            digest?.update(bytes, 0, count)
            fileOperation(FileStage.OUTPUT) { output.write(bytes, 0, count) }
            progress(total)
        }
        return total
    }

    private fun checkSpace(required: Long) {
        val available = space.bytes(root)
        if (available <= 0L || available < required) {
            throw DstuException(DstuError.internal("RESOURCE_INSUFFICIENT_SPACE", "insufficient resource storage space"))
        }
    }

    private fun ensureRoot() {
        if (Files.isSymbolicLink(root.parent) || !Files.isDirectory(root.parent, NOFOLLOW_LINKS)) storageFailure()
        try { Files.createDirectory(root) } catch (_: java.nio.file.FileAlreadyExistsException) { }
        if (Files.isSymbolicLink(root) || !Files.isDirectory(root, NOFOLLOW_LINKS)) storageFailure()
    }

    private fun ensureDirectory(path: Path) {
        try { Files.createDirectory(path) } catch (_: java.nio.file.FileAlreadyExistsException) { }
        if (Files.isSymbolicLink(path) || !Files.isDirectory(path, NOFOLLOW_LINKS)) storageFailure()
    }

    private fun resolve(relative: String, hash: String): Path {
        if (!HASH.matches(hash) || !Regex("${hash.take(2)}/${hash}\\.[A-Za-z0-9]{1,16}").matches(relative)) corruptAttachment()
        val target = root.resolve(relative).normalize()
        if (!target.startsWith(root) || Files.isSymbolicLink(target.parent) || Files.isSymbolicLink(target)) corruptAttachment()
        return target
    }

    private fun targetForMarker(path: Path): Path? {
        val match = PENDING.matchEntire(path.fileName.toString()) ?: return null
        return resolve("${match.groupValues[1].take(2)}/${match.groupValues[1]}.${match.groupValues[2]}", match.groupValues[1])
    }
    private fun markerHash(path: Path): String = PENDING.matchEntire(path.fileName.toString())?.groupValues?.get(1) ?: storageFailure()

    private fun quarantineForMarker(path: Path): Path? {
        val match = PENDING.matchEntire(path.fileName.toString()) ?: return null
        return root.resolve(".reap-${match.groupValues[3]}.tmp")
    }

    private inline fun withUnlockedFile(path: Path, action: () -> Unit) {
        try {
            FileChannel.open(path, StandardOpenOption.WRITE, NOFOLLOW_LINKS).use { channel ->
                val lock = try { channel.tryLock() } catch (_: OverlappingFileLockException) { null }
                lock?.use { action() }
            }
        } catch (_: java.nio.file.NoSuchFileException) { }
    }

    companion object {
        internal val publicationLock = Any()
        internal val livePrepared = mutableSetOf<Path>()
        private const val BUFFER_SIZE = 64 * 1024
        const val MAX_BYTES = 200L * 1024 * 1024
        private val HASH = Regex("[0-9a-f]{64}")
        private const val UUID_V4 = "[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"
        private val PREPARE = Regex("\\.prepare-$UUID_V4\\.tmp")
        private val PENDING = Regex("\\.pending-([0-9a-f]{64})-([A-Za-z0-9]{1,16})-($UUID_V4)\\.meta")
        private val LEGACY_PENDING = Regex("\\.pending-([0-9a-f]{64})-([A-Za-z0-9]{1,16})-$UUID_V4\\.tmp")
    }
}

internal class PreparedBlob(
    var path: Path, val channel: FileChannel, private val lock: FileLock, private val faults: ResourceFaults,
) : AutoCloseable {
    var hash: String = ""
    var size: Long = 0
    var marker: Path? = null
    var published: Boolean = false
    val pending: Boolean get() = marker != null
    override fun close() = synchronized(BlobFiles.publicationLock) {
        try { lock.release() } finally {
            try { channel.close() } finally {
                BlobFiles.livePrepared.remove(path)
                // An unsettled marker must survive so the next DB-aware recovery can decide ownership.
                if (!pending && !published) fileOperation(FileStage.CLEANUP) { Files.deleteIfExists(path) }
                faults.hit(ResourceCut.AFTER_COMMIT_CLOSE, size)
            }
        }
    }

}

internal fun invalidSize(): Nothing =
    throw DstuException(DstuError.validation("INVALID_ATTACHMENT_SIZE", "attachment size is invalid"))

private fun MessageDigest.hex(): String = digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }

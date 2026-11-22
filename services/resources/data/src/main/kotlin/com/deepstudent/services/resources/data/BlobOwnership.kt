package com.deepstudent.services.resources.data

import com.deepstudent.core.common.error.DstuError
import com.deepstudent.core.common.error.DstuException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.FileTime

/** Unix identity includes ctime to reject inode reuse after an interrupted writer exits. */
internal data class BlobIdentity(
    val device: Long, val inode: Long, val size: Long,
    val modifiedSeconds: Long, val modifiedNanos: Long, val changedSeconds: Long, val changedNanos: Long,
) {
    fun sameMovedFile(other: BlobIdentity): Boolean =
        device == other.device && inode == other.inode && size == other.size &&
            modifiedSeconds == other.modifiedSeconds && modifiedNanos == other.modifiedNanos

    fun fields(): String = listOf(device, inode, size, modifiedSeconds, modifiedNanos, changedSeconds, changedNanos).joinToString(" ")
}

internal fun interface BlobIdentityReader {
    fun read(path: Path): BlobIdentity?

    companion object {
        val REAL = BlobIdentityReader { path -> readUnixIdentity(path) }
    }
}

private fun readUnixIdentity(path: Path): BlobIdentity? {
    if (!Files.isRegularFile(path, NOFOLLOW_LINKS)) return null
    val attributes = try {
        Files.readAttributes(path, "unix:dev,ino,size,lastModifiedTime,ctime", NOFOLLOW_LINKS)
    } catch (_: UnsupportedOperationException) { return null }
      catch (_: IllegalArgumentException) { return null }
    return identityFromAttributes(attributes)
}

private fun identityFromAttributes(attributes: Map<String, Any>): BlobIdentity? {
    val device = (attributes["dev"] as? Number)?.toLong() ?: return null
    val inode = (attributes["ino"] as? Number)?.toLong() ?: return null
    val size = (attributes["size"] as? Number)?.toLong() ?: return null
    val modified = (attributes["lastModifiedTime"] as? FileTime)?.toInstant() ?: return null
    val changed = (attributes["ctime"] as? FileTime)?.toInstant() ?: return null
    if (inode <= 0 || size < 0) return null
    return BlobIdentity(device, inode, size, modified.epochSecond, modified.nano.toLong(), changed.epochSecond, changed.nano.toLong())
}

internal data class OwnerRecord(val source: BlobIdentity, val published: BlobIdentity?)

/** Only fixed-size identity data is persisted; never contains URI or business content. */
internal object BlobOwnership {
    private const val MAX_MARKER = 512
    private const val HEADER = "DSTU-BLOB-OWNER-1\n"

    fun create(path: Path, source: BlobIdentity) {
        FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, NOFOLLOW_LINKS).use { channel ->
            write(channel, "$HEADER${source.fields()}\n")
        }
    }

    fun recordPublication(path: Path, published: BlobIdentity) {
        FileChannel.open(path, StandardOpenOption.WRITE, StandardOpenOption.APPEND, NOFOLLOW_LINKS).use { channel ->
            write(channel, "${published.fields()}\n")
        }
    }

    fun read(path: Path): OwnerRecord? {
        val bytes = ByteBuffer.allocate(MAX_MARKER + 1)
        FileChannel.open(path, StandardOpenOption.READ, NOFOLLOW_LINKS).use { channel ->
            while (bytes.hasRemaining() && channel.read(bytes) > 0) { }
        }
        if (bytes.position() > MAX_MARKER) return null
        bytes.flip()
        return parseRecord(StandardCharsets.US_ASCII.decode(bytes).toString())
    }

    private fun parseRecord(text: String): OwnerRecord? {
        val lines = text.split('\n')
        if (lines.size !in 3..4 || lines[0] != HEADER.trimEnd() || lines.last() != "") return null
        val source = parse(lines[1]) ?: return null
        val published = if (lines.size == 4) parse(lines[2]) ?: return null else null
        if (published != null && !source.sameMovedFile(published)) return null
        return OwnerRecord(source, published)
    }

    private fun write(channel: FileChannel, text: String) {
        val data = StandardCharsets.US_ASCII.encode(text)
        while (data.hasRemaining()) channel.write(data)
        channel.force(true)
    }

    private fun parse(line: String): BlobIdentity? {
        val parts = line.split(' ')
        if (parts.size != 7) return null
        val numbers = parts.map { it.toLongOrNull() ?: return null }
        if (numbers[1] <= 0 || numbers[2] < 0 || numbers[4] !in 0..999_999_999 || numbers[6] !in 0..999_999_999) return null
        return BlobIdentity(numbers[0], numbers[1], numbers[2], numbers[3], numbers[4], numbers[5], numbers[6])
    }
}

internal fun ownershipDiagnostic(): DstuException = DstuException(
    DstuError.internal("RESOURCE_OWNERSHIP_UNKNOWN", "resource ownership could not be verified"),
)

internal fun ownershipFailure(): Nothing = throw ownershipDiagnostic()

package com.deepstudent.app.backup

import com.deepstudent.core.common.id.Digests
import com.deepstudent.services.backup.api.PlannedFile
import com.deepstudent.services.resources.data.assets.VfsRestoreBlob
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.READ
import java.security.MessageDigest

internal enum class RestoreBlobError { BLOB_NOT_IN_ARCHIVE, BLOB_METADATA_MISMATCH, UNSAFE_BLOB_PATH, BLOB_HASH_OR_SIZE }
internal class RestoreBlobException(val code: RestoreBlobError) : IllegalStateException(code.name)

internal class RestoreBlobFiles(private val archived: Map<String, PlannedFile>) {
    fun verify(root: Path, blob: VfsRestoreBlob) {
        val file = candidatePath(root, blob.relative)
        if (!SHA256.matches(blob.hash)) fail(RestoreBlobError.BLOB_METADATA_MISMATCH)
        val entry = archived["vfs_blobs/${blob.relative}"]
        if (entry == null && blob.required) fail(RestoreBlobError.BLOB_NOT_IN_ARCHIVE)
        if (entry != null) verifyDeclaration(entry, blob)
        if (entry == null && Files.notExists(file, NOFOLLOW_LINKS)) return
        verifyFile(file, blob)
    }

    private fun verifyDeclaration(entry: PlannedFile, blob: VfsRestoreBlob) {
        if (entry.size != blob.length || !entry.sha256.equals(blob.hash, ignoreCase = true)) {
            fail(RestoreBlobError.BLOB_METADATA_MISMATCH)
        }
    }

    private fun verifyFile(file: Path, blob: VfsRestoreBlob) {
        if (!Files.isRegularFile(file, NOFOLLOW_LINKS) || Files.size(file) != blob.length ||
            !hashFile(file, blob.length).equals(blob.hash, ignoreCase = true)
        ) fail(RestoreBlobError.BLOB_HASH_OR_SIZE)
    }

    private fun candidatePath(root: Path, relative: String): Path {
        val parts = relative.split('/')
        if (relative.length !in 1..4096 || relative.any(::unsafeCharacter) || parts.any(::unsafeComponent)) {
            fail(RestoreBlobError.UNSAFE_BLOB_PATH)
        }
        return parts.fold(root) { parent, name ->
            if (!Files.isDirectory(parent, NOFOLLOW_LINKS) && !Files.notExists(parent, NOFOLLOW_LINKS)) {
                fail(RestoreBlobError.UNSAFE_BLOB_PATH)
            }
            parent.resolve(name)
        }
    }

    private fun unsafeComponent(part: String) = part.isEmpty() || part == "." || part == ".."
    private fun unsafeCharacter(char: Char) = char == '\\' || char == ':' || char.isISOControl()

    private fun hashFile(file: Path, length: Long): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file, READ, NOFOLLOW_LINKS).use { stream ->
            val buffer = ByteArray(64 * 1024)
            var left = length
            while (true) {
                val count = stream.read(buffer)
                if (count == -1) break
                if (count <= 0 || count > left) fail(RestoreBlobError.BLOB_HASH_OR_SIZE)
                digest.update(buffer, 0, count)
                left -= count
            }
            if (left != 0L) fail(RestoreBlobError.BLOB_HASH_OR_SIZE)
        }
        return Digests.toHex(digest.digest())
    }

    private fun fail(code: RestoreBlobError): Nothing = throw RestoreBlobException(code)

    private companion object { val SHA256 = Regex("[0-9a-fA-F]{64}") }
}

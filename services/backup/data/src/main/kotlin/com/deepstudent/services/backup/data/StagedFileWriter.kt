package com.deepstudent.services.backup.data

import com.deepstudent.services.backup.api.PlannedFile
import com.deepstudent.services.backup.api.RestoreArchive
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import java.util.UUID

internal fun hashStream(input: InputStream, output: OutputStream?, limit: Long, trace: RestoreTrace): Pair<Long, String> {
    val hash = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(64 * 1024)
    var bytes = 0L
    while (true) {
        trace.checkCancelled()
        val read = input.read(buffer)
        if (read < 0) break
        if (read == 0) throw RestoreFailure(RestoreError.IO_FAILURE)
        bytes = checkedSum(bytes, read.toLong(), limit)
        hash.update(buffer, 0, read)
        output?.write(buffer, 0, read)
    }
    return bytes to hash.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

internal object StagedFileWriter {
    fun manifest(archive: RestoreArchive, expected: String, trace: RestoreTrace) {
        val (_, actual) = archive.open("manifest.json").use { hashStream(it, null, MAX_MANIFEST_BYTES, trace) }
        if (!actual.equals(expected, ignoreCase = true)) throw RestoreFailure(RestoreError.MANIFEST_CHANGED)
    }

    fun write(root: Path, archive: RestoreArchive, file: PlannedFile, trace: RestoreTrace) {
        val target = targetPath(root, file.slotRelativePath)
        val temp = target.parent.resolve(".dstu-restore-${UUID.randomUUID()}.tmp")
        try {
            copyAndVerify(archive, file, temp, trace)
            if (Files.exists(target, NOFOLLOW_LINKS) && !Files.isRegularFile(target, NOFOLLOW_LINKS)) {
                throw RestoreFailure(RestoreError.UNTRUSTED_TARGET)
            }
            Files.move(temp, target, ATOMIC_MOVE, REPLACE_EXISTING)
            removeDatabaseSidecars(target, file.slotRelativePath)
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    private fun copyAndVerify(archive: RestoreArchive, file: PlannedFile, temp: Path, trace: RestoreTrace) {
        Files.newOutputStream(temp, CREATE_NEW, WRITE, NOFOLLOW_LINKS).use { out ->
            archive.open(file.archivePath).use { input ->
                val (count, digest) = hashStream(input, out, file.size, trace)
                if (count != file.size) throw RestoreFailure(RestoreError.SIZE_MISMATCH)
                if (!digest.equals(file.sha256, ignoreCase = true)) throw RestoreFailure(RestoreError.HASH_MISMATCH)
            }
        }
    }

    private fun targetPath(root: Path, relative: String): Path {
        val parts = relative.split('/')
        var parent = root
        for (part in parts.dropLast(1)) {
            parent = parent.resolve(part)
            if (!Files.exists(parent, NOFOLLOW_LINKS)) Files.createDirectory(parent)
            if (!Files.isDirectory(parent, NOFOLLOW_LINKS)) throw RestoreFailure(RestoreError.UNTRUSTED_TARGET)
        }
        return parent.resolve(parts.last())
    }

    private fun removeDatabaseSidecars(target: Path, relative: String) {
        if (DATABASE_IDS.none { relative == "databases/$it.db" }) return
        for (suffix in listOf("-wal", "-shm", "-journal")) {
            val sidecar = target.resolveSibling(target.fileName.toString() + suffix)
            if (Files.exists(sidecar, NOFOLLOW_LINKS)) {
                if (!Files.isRegularFile(sidecar, NOFOLLOW_LINKS)) throw RestoreFailure(RestoreError.UNTRUSTED_TARGET)
                Files.delete(sidecar)
            }
        }
    }
}

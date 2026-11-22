package com.deepstudent.platform.security

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions

internal object SecureFiles {
    private const val BUFFER_SIZE = 8192
    private const val MAX_ERASE_BYTES = 16L * 1024L * 1024L
    private const val TEMP_PREFIX = ".dstu-"
    private const val TEMP_SUFFIX = ".tmp"

    fun ensureDirectory(directory: File) {
        rejectSymlink(directory)
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
            throw IOException("directory is unavailable")
        }
        rejectSymlink(directory)
        ownerOnly(directory)
    }

    fun requireDirectory(directory: File) {
        if (!directory.isDirectory) throw IOException("directory is unavailable")
        rejectSymlink(directory)
    }

    fun readBounded(file: File, maxBytes: Int): ByteArray {
        require(maxBytes >= 0) { "byte budget must not be negative" }
        rejectSymlink(file)
        val channel = try {
            FileChannel.open(
                file.toPath(),
                StandardOpenOption.READ,
                LinkOption.NOFOLLOW_LINKS,
            )
        } catch (error: IOException) {
            throw error
        } catch (error: Exception) {
            throw IOException("file is unavailable", error)
        }
        channel.use {
            if (channel.size() > maxBytes.toLong()) throw IOException("file exceeds its budget")
            val output = ByteArrayOutputStream(minOf(maxBytes, BUFFER_SIZE))
            val buffer = ByteBuffer.allocate(BUFFER_SIZE)
            var total = 0
            while (true) {
                buffer.clear()
                val read = channel.read(buffer)
                if (read < 0) break
                total += read
                if (total > maxBytes) throw IOException("file exceeds its budget")
                output.write(buffer.array(), 0, read)
            }
            return output.toByteArray()
        }
    }

    fun writeAtomic(file: File, bytes: ByteArray) {
        val target = file.absoluteFile
        val parent = target.parentFile ?: throw IOException("file has no parent")
        ensureDirectory(parent)
        rejectSymlink(target)
        val temp = Files.createTempFile(parent.toPath(), TEMP_PREFIX, TEMP_SUFFIX).toFile()
        try {
            ownerOnly(temp)
            FileChannel.open(
                temp.toPath(),
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING,
                LinkOption.NOFOLLOW_LINKS,
            ).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            ownerOnly(temp)
            replaceAtomically(temp, target)
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    fun replaceAtomically(source: File, target: File) {
        rejectSymlink(source)
        rejectSymlink(target)
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (error: java.nio.file.AtomicMoveNotSupportedException) {
            throw IOException("atomic move is unavailable", error)
        } catch (error: UnsupportedOperationException) {
            throw IOException("atomic move is unavailable", error)
        }
    }

    fun ownerOnly(file: File) {
        rejectSymlink(file)
        val permissions = if (file.isDirectory) "rwx------" else "rw-------"
        Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString(permissions))
    }

    fun deleteBestEffort(file: File): Boolean {
        if (!file.exists() && !isSymlink(file)) return true
        return runCatching { Files.deleteIfExists(file.toPath()) }.getOrDefault(false)
    }

    /** Best-effort overwrite of a failed staging or credential record before removal. */
    fun wipeAndDelete(file: File): Boolean {
        if (isSymlink(file)) return deleteBestEffort(file)
        if (!file.isFile) return deleteBestEffort(file)
        runCatching {
            FileChannel.open(
                file.toPath(),
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS,
            ).use { channel ->
                val zeros = ByteArray(BUFFER_SIZE)
                // Flash/COW media cannot promise physical erasure. Bound this
                // best-effort overwrite so an orphan's forged length cannot
                // turn recovery into an unbounded disk/CPU operation.
                var remaining = minOf(channel.size(), MAX_ERASE_BYTES)
                while (remaining > 0) {
                    val count = minOf(remaining, zeros.size.toLong()).toInt()
                    val buffer = ByteBuffer.wrap(zeros, 0, count)
                    while (buffer.hasRemaining()) channel.write(buffer)
                    remaining -= count
                }
                channel.force(true)
            }
        }
        return deleteBestEffort(file)
    }

    fun rejectSymlink(file: File) {
        var current: File? = file.absoluteFile
        while (current != null) {
            if (isSymlink(current)) throw IOException("symbolic links are not allowed")
            current = current.parentFile
        }
    }

    fun isSymlink(file: File): Boolean =
        Files.isSymbolicLink(file.toPath())
}

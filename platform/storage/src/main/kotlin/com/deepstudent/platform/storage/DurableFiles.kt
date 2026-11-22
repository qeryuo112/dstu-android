package com.deepstudent.platform.storage

import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.TRUNCATE_EXISTING
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes

internal object DurableFiles {
    fun exists(file: File): Boolean = Files.exists(file.toPath(), NOFOLLOW_LINKS)

    fun directory(file: File) {
        val attr = attributes(file)
        if (!attr.isDirectory || attr.isSymbolicLink) throw DataSpaceException(DataSpaceError.UNSAFE_ENTRY)
    }

    fun regular(file: File) {
        val attr = attributes(file)
        if (!attr.isRegularFile || attr.isSymbolicLink) throw DataSpaceException(DataSpaceError.UNSAFE_ENTRY)
    }

    fun createDirectory(file: File) {
        if (!exists(file)) {
            try { Files.createDirectory(file.toPath()) } catch (_: java.nio.file.FileAlreadyExistsException) { }
            directory(file)
            syncDirectory(checkNotNull(file.parentFile))
        } else directory(file)
    }

    fun children(dir: File): List<File> {
        directory(dir)
        return Files.newDirectoryStream(dir.toPath()).use { stream -> stream.map(Path::toFile).toList() }
    }

    fun readBounded(file: File): ByteArray {
        regular(file)
        if (Files.size(file.toPath()) > 256) throw DataSpaceException(DataSpaceError.CORRUPT_STATE)
        return Files.readAllBytes(file.toPath())
    }

    fun writeTemp(file: File, bytes: ByteArray) {
        if (exists(file)) regular(file)
        FileChannel.open(file.toPath(), CREATE, WRITE, TRUNCATE_EXISTING, NOFOLLOW_LINKS).use { channel ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
        }
    }

    fun replace(temp: File, target: File) {
        regular(temp)
        if (exists(target)) regular(target)
        Files.move(temp.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
    }

    fun writeAtomic(target: File, temp: File, bytes: ByteArray) {
        writeTemp(temp, bytes)
        replace(temp, target)
        syncDirectory(checkNotNull(target.parentFile))
    }

    fun syncDirectory(dir: File) {
        directory(dir)
        FileChannel.open(dir.toPath(), READ, NOFOLLOW_LINKS).use { it.force(true) }
    }

    /** No links or special files are traversed. Each file and then each directory is forced. */
    fun syncTree(dir: File) {
        directory(dir)
        children(dir).forEach { file -> syncEntry(file) }
        syncDirectory(dir)
    }

    private fun syncEntry(file: File) {
        val attr = attributes(file)
        when {
            attr.isSymbolicLink -> throw DataSpaceException(DataSpaceError.UNSAFE_ENTRY)
            attr.isDirectory -> syncTree(file)
            attr.isRegularFile -> FileChannel.open(file.toPath(), READ, NOFOLLOW_LINKS).use { it.force(true) }
            else -> throw DataSpaceException(DataSpaceError.UNSAFE_ENTRY)
        }
    }

    fun inspectTree(dir: File) {
        directory(dir)
        children(dir).forEach { file -> inspectEntry(file) }
    }

    private fun inspectEntry(file: File) {
        val attr = attributes(file)
        when {
            attr.isSymbolicLink -> throw DataSpaceException(DataSpaceError.UNSAFE_ENTRY)
            attr.isDirectory -> inspectTree(file)
            !attr.isRegularFile -> throw DataSpaceException(DataSpaceError.UNSAFE_ENTRY)
        }
    }

    fun deleteTree(dir: File) {
        inspectTree(dir)
        deleteChecked(dir)
        syncDirectory(checkNotNull(dir.parentFile))
    }

    private fun deleteChecked(file: File) {
        val attr = attributes(file)
        if (attr.isSymbolicLink || (!attr.isDirectory && !attr.isRegularFile)) {
            throw DataSpaceException(DataSpaceError.UNSAFE_ENTRY)
        }
        if (attr.isDirectory) children(file).forEach { deleteChecked(it) }
        Files.delete(file.toPath())
    }

    private fun attributes(file: File): BasicFileAttributes =
        Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
}

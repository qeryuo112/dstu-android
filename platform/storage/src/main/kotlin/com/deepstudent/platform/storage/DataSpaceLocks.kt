package com.deepstudent.platform.storage

import java.io.File
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE


/** One JVM owner per canonical path; one shared OS lock is reused by local readers. */
internal object DataSpaceLocks {
    private val owners = mutableMapOf<String, DataSpaceLockOwner>()

    fun shared(file: File): AutoCloseable = acquire(file, shared = true)
    fun maintenance(file: File): AutoCloseable = acquire(file, shared = false)

    @Synchronized private fun acquire(file: File, shared: Boolean): AutoCloseable {
        val key = file.absolutePath
        val owner = owners.getOrPut(key) { DataSpaceLockOwner(file) }
        try {
            val token = if (shared) owner.shared() else owner.maintenance()
            return LockToken { release(key, owner, token) }
        } catch (failure: Exception) {
            if (owner.idle()) owners.remove(key, owner)
            throw failure
        }
    }

    @Synchronized private fun release(key: String, owner: DataSpaceLockOwner, token: AutoCloseable) {
        try { token.close() } finally { if (owner.idle()) owners.remove(key, owner) }
    }

    @Synchronized fun sizeForTest(): Int = owners.size
}

internal class DataSpaceLockOwner(private val lockFile: File) {
    private var held: Held? = null
    private var readers = 0
    private var exclusive = false

    @Synchronized fun idle(): Boolean = readers == 0 && !exclusive

    @Synchronized fun shared(): AutoCloseable {
        if (exclusive) busy()
        if (readers == 0) held = open(shared = true)
        readers++
        return LockToken { releaseShared() }
    }

    @Synchronized fun maintenance(): AutoCloseable {
        if (exclusive || readers != 0) busy()
        held = open(shared = false)
        exclusive = true
        return LockToken { releaseMaintenance() }
    }

    @Synchronized private fun releaseShared() {
        readers--
        if (readers == 0) releaseHeld()
    }

    @Synchronized private fun releaseMaintenance() {
        exclusive = false
        releaseHeld()
    }

    private fun open(shared: Boolean): Held {
        if (DurableFiles.exists(lockFile)) DurableFiles.regular(lockFile)
        val channel = FileChannel.open(lockFile.toPath(), CREATE, WRITE, NOFOLLOW_LINKS)
        try {
            DurableFiles.regular(lockFile)
            // A shared file lock requires a readable channel on the JVM and Android.
            channel.close()
            return lockOpened(shared)
        } catch (failure: Exception) {
            channel.close()
            throw failure
        }
    }

    private fun lockOpened(shared: Boolean): Held {
        val channel = FileChannel.open(lockFile.toPath(), java.nio.file.StandardOpenOption.READ, WRITE, NOFOLLOW_LINKS)
        try {
            val lock = try { channel.tryLock(0L, Long.MAX_VALUE, shared) } catch (_: OverlappingFileLockException) { null }
            if (lock == null) busy()
            return Held(channel, lock)
        } catch (failure: Exception) {
            channel.close()
            throw failure
        }
    }

    private fun releaseHeld() {
        val previous = checkNotNull(held)
        held = null
        try { previous.lock.release() } finally { previous.channel.close() }
    }

    private fun busy(): Nothing = throw DataSpaceException(DataSpaceError.BUSY)
    private data class Held(val channel: FileChannel, val lock: FileLock)
}

private class LockToken(private val release: () -> Unit) : AutoCloseable {
    private var closed = false
    @Synchronized override fun close() {
        if (!closed) {
            closed = true
            release()
        }
    }
}

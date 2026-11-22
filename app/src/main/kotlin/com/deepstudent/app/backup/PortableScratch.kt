package com.deepstudent.app.backup

import java.io.File
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.util.concurrent.ConcurrentHashMap

/** Private scratch is locked for its lifetime; startup can remove only abandoned operations. */
internal class PortableScratch private constructor(
    private val root: File, val directory: File, private val channel: FileChannel, private val lock: FileLock,
    private val cleanupFailed: () -> Unit,
) : AutoCloseable {
    fun file(name: String): File = File(directory, name)

    // Cleanup runs after publication. A cleanup failure must not turn a successful atomic
    // publication into a reported failure; the locked orphan can be retried at startup.
    override fun close() {
        try {
            guard(root) {
                try { lock.release() } finally { channel.close() }
                if (!directory.deleteRecursively()) cleanupFailed()
            }
        } catch (_: Exception) {
            // Even if the shared guard becomes unavailable, do not keep our operation lock alive.
            try { if (lock.isValid) lock.release() } catch (_: Exception) { }
            try { if (channel.isOpen) channel.close() } catch (_: Exception) { }
            cleanupFailed()
        }
    }

    companion object {
        private const val PREFIX = ".portable-work-"
        private const val LOCK = ".lock"
        private val processGuards = ConcurrentHashMap<String, Any>()

        fun create(root: File, cleanupFailed: () -> Unit = {}): PortableScratch = guard(root) {
            val directory = Files.createTempDirectory(root.toPath(), PREFIX).toFile()
            try {
                val channel = FileChannel.open(File(directory, LOCK).toPath(), CREATE_NEW, READ, WRITE, NOFOLLOW_LINKS)
                try { PortableScratch(root, directory, channel, channel.lock(), cleanupFailed) }
                catch (error: Exception) { channel.close(); throw error }
            } catch (error: Exception) { directory.deleteRecursively(); throw error }
        }

        fun cleanupAbandoned(root: File): Int = guard(root) {
            var count = 0
            root.listFiles()?.filter { it.name.startsWith(PREFIX) && !Files.isSymbolicLink(it.toPath()) && it.isDirectory }
                ?.forEach { dir -> if (removeIfAbandoned(dir)) count++ }
            count
        }

        private fun removeIfAbandoned(dir: File): Boolean {
            val path = File(dir, LOCK).toPath()
            if (!Files.exists(path, NOFOLLOW_LINKS)) return dir.deleteRecursively()
            return try {
                FileChannel.open(path, READ, WRITE, NOFOLLOW_LINKS).use { channel ->
                    val lock = channel.tryLock() ?: return false
                    try { dir.deleteRecursively() } finally { lock.release() }
                }
            } catch (_: OverlappingFileLockException) { false }
        }

        private fun <T> guard(root: File, action: () -> T): T {
            val identity = root.canonicalPath
            return synchronized(processGuards.computeIfAbsent(identity) { Any() }) {
                FileChannel.open(File(root, ".portable.guard").toPath(), CREATE, READ, WRITE, NOFOLLOW_LINKS).use { channel ->
                    channel.lock().use { action() }
                }
            }
        }
    }
}

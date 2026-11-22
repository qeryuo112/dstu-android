package com.deepstudent.platform.security

import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap

class DsbkStagingArea(
    private val directory: File,
    private val maxStagedBytes: Long,
) : AutoCloseable {
    private val state: StagingDirectoryState
    private val owned = mutableSetOf<StagingSink>()
    private var closed = false

    init {
        require(maxStagedBytes > 0) { "staging budget must be positive" }
        try {
            SecureFiles.ensureDirectory(directory)
            state = STATES.computeIfAbsent(directory.canonicalPath) { StagingDirectoryState() }
        } catch (_: IOException) {
            throw DsbkException(DsbkError.STAGING_FAILED)
        }
    }

    fun createSink(target: File, limit: Long = maxStagedBytes): DsbkSink = synchronized(state.lock) {
        val budget = minOf(limit, maxStagedBytes)
        if (budget <= 0) throw DsbkException(DsbkError.OUTPUT_LIMIT_EXCEEDED)
        if (closed) throw DsbkException(DsbkError.STAGING_FAILED)
        try {
            val destination = validateTarget(target)
            val sink = SecureFileMutex.withLock(File(directory, DIRECTORY_LOCK)) {
                StagingSink(destination, budget, directory, state, maxStagedBytes) { finished ->
                    owned.remove(finished)
                    state.active.remove(finished)
                }
            }
            owned += sink
            state.active += sink
            sink
        } catch (error: DsbkException) {
            throw error
        } catch (_: Exception) {
            throw DsbkException(DsbkError.STAGING_FAILED)
        }
    }

    private fun validateTarget(target: File): File {
        SecureFiles.requireDirectory(directory)
        val destination = target.absoluteFile
        val parent = destination.parentFile ?: throw IOException("target has no parent")
        SecureFiles.ensureDirectory(parent)
        SecureFiles.rejectSymlink(destination)
        if (destination.isDirectory) throw DsbkException(DsbkError.STAGING_FAILED)
        if (parent.canonicalFile == directory.canonicalFile && isPart(destination)) {
            throw DsbkException(DsbkError.STAGING_FAILED)
        }
        if (state.active.any { it.targetPath == destination.canonicalPath }) {
            throw DsbkException(DsbkError.STAGING_FAILED)
        }
        return destination
    }

    fun cleanupOrphans(): Int = synchronized(state.lock) {
        try {
            SecureFiles.requireDirectory(directory)
            SecureFileMutex.withLock(File(directory, DIRECTORY_LOCK)) {
                val activeNames = state.active.map { it.tempName }.toSet()
                directory.listFiles()?.count { file ->
                    isPart(file) && file.name !in activeNames && removeUnlockedPart(file)
                } ?: 0
            }
        } catch (_: Exception) {
            throw DsbkException(DsbkError.STAGING_FAILED)
        }
    }

    override fun close() = synchronized(state.lock) {
        if (!closed) {
            closed = true
            owned.toList().forEach { it.abort() }
            cleanupOrphans()
        }
    }

    private fun removeUnlockedPart(file: File): Boolean {
        if (SecureFiles.isSymlink(file)) return SecureFiles.deleteBestEffort(file)
        if (!file.isFile) return false
        return try {
            FileChannel.open(file.toPath(), StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { channel ->
                val held = channel.tryLock() ?: return false // A live owner in another process.
                try {
                    SecureFiles.wipeAndDelete(file)
                } finally {
                    held.release()
                }
            }
        } catch (_: OverlappingFileLockException) {
            false
        } catch (_: IOException) {
            false
        }
    }

    private fun isPart(file: File): Boolean =
        file.name.startsWith(".dstu-") && file.name.endsWith(".part")

    companion object {
        private const val DIRECTORY_LOCK = ".staging.lock"
        private val STATES = ConcurrentHashMap<String, StagingDirectoryState>()
    }
}

internal class StagingDirectoryState {
    val lock = Any()
    val active = mutableSetOf<StagingSink>()
    var stagedBytes = 0L
}

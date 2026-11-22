package com.deepstudent.services.sync.data.transfer

import com.deepstudent.services.sync.api.SyncError
import com.deepstudent.services.sync.backend.SyncFailure
import java.io.File
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption

class SyncProcessLock private constructor(private val channel: FileChannel, private val lock: FileLock) : AutoCloseable {
    override fun close() {
        try { lock.release() } finally { channel.close() }
    }

    companion object {
        fun acquire(directory: File): SyncProcessLock {
            val path = directory.toPath()
            if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) unavailable()
            val target = File(directory, "transfer.lock").toPath()
            val channel = FileChannel.open(target, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
            return try {
                SyncProcessLock(channel, channel.tryLock() ?: unavailable())
            } catch (_: Exception) {
                channel.close()
                unavailable()
            }
        }

        private fun unavailable(): Nothing = throw SyncFailure(SyncError.ALREADY_EXISTS)
    }
}

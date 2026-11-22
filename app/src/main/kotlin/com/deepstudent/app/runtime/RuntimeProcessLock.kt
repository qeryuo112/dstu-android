package com.deepstudent.app.runtime

import java.io.File
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.util.UUID

/** Holding this lock proves no previous main-process runtime owner is still using this directory. */
internal class RuntimeProcessLock private constructor(
    private val channel: FileChannel,
    private val lock: FileLock,
) : AutoCloseable {
    val ownerId = "main:${UUID.randomUUID()}"

    override fun close() {
        try { lock.release() } finally { channel.close() }
    }

    companion object {
        fun acquire(directory: File): RuntimeProcessLock {
            val channel = FileChannel.open(File(directory, "runtime.owner.lock").toPath(),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
            try {
                val lock = channel.tryLock() ?: error("runtime owner already active")
                return RuntimeProcessLock(channel, lock)
            } catch (failure: Exception) {
                channel.close()
                throw failure
            }
        }
    }
}

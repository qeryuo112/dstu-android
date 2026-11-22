package com.deepstudent.platform.security

import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

/** JVM reentrancy plus an OS lock; lock files must never be removed while in use. */
internal object SecureFileMutex {
    private val locks = ConcurrentHashMap<String, ReentrantLock>()

    fun <T> withLock(file: File, block: () -> T): T {
        SecureFiles.rejectSymlink(file.absoluteFile)
        val lock = locks.computeIfAbsent(file.canonicalPath) { ReentrantLock() }
        lock.lock()
        try {
            // A credential transaction may call load/delete on its own master.
            // Reacquiring the OS lock here would throw OverlappingFileLockException.
            if (lock.holdCount > 1) return block()
            return FileChannel.open(
                file.toPath(),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS,
            ).use { channel ->
                SecureFiles.ownerOnly(file)
                val held = channel.lock()
                try {
                    block()
                } finally {
                    held.release()
                }
            }
        } finally {
            lock.unlock()
        }
    }
}

package com.deepstudent.features.workspace.data

import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID

internal object AtomicFileWriter {
    fun write(target: File, bytes: ByteArray) {
        val parent = target.parentFile ?: error("workspace target directory is unavailable")
        parent.mkdirs()
        val temporary = File(parent, "${target.name}.tmp-${UUID.randomUUID()}")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                output.flush()
                output.fd.sync()
            }
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            syncDirectory(parent)
        } finally {
            temporary.delete()
        }
    }

    private fun syncDirectory(directory: File) {
        try {
            FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
        } catch (_: Exception) {
            // The file itself is fsynced; directory sync is best effort on Android filesystems.
        }
    }
}

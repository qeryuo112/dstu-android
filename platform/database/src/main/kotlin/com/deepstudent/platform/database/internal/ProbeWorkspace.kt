package com.deepstudent.platform.database.internal

import java.io.File
import java.util.UUID

/** Owns the temporary probe directory and removes it even when a probe fails. */
internal class ProbeWorkspace private constructor(
    val directory: File,
) : AutoCloseable {

    fun file(name: String): File = File(directory, name)

    override fun close() {
        directory.deleteRecursively()
    }

    companion object {
        fun create(root: File): ProbeWorkspace {
            check(root.mkdirs() || root.isDirectory) {
                "Probe work directory is not writable: ${root.absolutePath}"
            }
            val directory = File(root, "s1-04-${UUID.randomUUID()}")
            check(directory.mkdirs()) {
                "Cannot create probe directory: ${directory.absolutePath}"
            }
            return ProbeWorkspace(directory)
        }
    }
}

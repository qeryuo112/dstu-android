package com.deepstudent.platform.database.internal

import com.deepstudent.platform.database.DatabaseConfigurationException
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes

/** Never open a second WAL owner through a hard link or after an active file is replaced. */
internal class DatabaseFileIdentity(private val file: File, val key: Any) {
    fun verify() {
        if (fileKey(file) != key) throw DatabaseConfigurationException()
    }
}

internal fun fileKey(file: File): Any? {
    if (!file.exists()) return null
    return try {
        Files.readAttributes(file.toPath(), BasicFileAttributes::class.java).fileKey()
            ?: throw DatabaseConfigurationException()
    } catch (_: Exception) {
        throw DatabaseConfigurationException()
    }
}

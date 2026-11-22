package com.deepstudent.app.backup

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.Comparator

/** Single main-process owner. Orphan cleanup never follows a symlink out of its own cache. */
internal class EncryptedBackupScratch(cache: File) {
    private val root = cache.canonicalFile.resolve("encrypted-backup-work")
    private var cleaned = false

    @Synchronized
    fun open(): File {
        Files.createDirectories(root.toPath())
        check(Files.isDirectory(root.toPath(), NOFOLLOW_LINKS))
        if (!cleaned) {
            Files.newDirectoryStream(root.toPath(), "op-*").use { paths -> paths.forEach(::removeOwnedTree) }
            cleaned = true
        }
        return Files.createTempDirectory(root.toPath(), "op-").toFile()
    }
}

internal fun removeOwnedTree(path: Path) {
    if (Files.notExists(path, NOFOLLOW_LINKS)) return
    Files.walk(path).use { entries -> entries.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
}

package com.deepstudent.features.environments.data

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

internal object SafeFileTree {
    fun deleteRecursivelyNoFollow(target: File) {
        val path = target.toPath()
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            Files.newDirectoryStream(path).use { children ->
                children.forEach { child -> deleteRecursivelyNoFollow(child.toFile()) }
            }
        }
        Files.deleteIfExists(path)
    }

    fun ensureDirectory(path: Path) {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            require(Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                "rootfs path is not a directory"
            }
            return
        }
        path.parent?.let(::ensureDirectory)
        Files.createDirectory(path)
    }
}

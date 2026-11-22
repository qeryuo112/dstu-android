package com.deepstudent.services.resources.data.assets

import com.deepstudent.services.resources.api.assets.AssetCleanupPolicy
import com.deepstudent.services.resources.api.assets.AssetCleanupResult
import com.deepstudent.services.resources.backend.assets.AssetScratchCleaner
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.UUID

/**
 * Own private staging only. [appCache] must be an app-private, trusted existing directory;
 * all users of this particular scratch directory must hold this API's lock. A callback must
 * not retain the file after it returns. Never point this at S2.06 or desktop asset/blob roots.
 */
class OwnedAssetScratch(appCache: File) : AssetScratchCleaner {
    private val parent = appCache.toPath().toAbsolutePath().normalize()
    private val root = parent.resolve("s208-owned-scratch")

    fun <T> withFile(block: (File) -> T): T = locked {
        val file = root.resolve("s208-${UUID.randomUUID()}.tmp")
        Files.createFile(file)
        try { block(file.toFile()) } finally { Files.deleteIfExists(file) }
    }

    /** Removes only regular, S2.08-named entries when no cooperating writer holds the lock. */
    override fun cleanup(): AssetCleanupResult = locked {
        var removed = 0
        var skipped = 0
        Files.newDirectoryStream(root).use { stream ->
            for (entry in stream) {
                if (entry.fileName.toString() == LOCK) continue
                if (OWNED.matches(entry.fileName.toString()) && Files.isRegularFile(entry, NOFOLLOW_LINKS)) {
                    Files.delete(entry)
                    removed++
                } else {
                    skipped++
                }
            }
        }
        AssetCleanupResult(AssetCleanupPolicy.REMOVE_S208_OWN_SCRATCH, removed, skipped)
    }

    private fun <T> locked(block: () -> T): T = synchronized(LOCK_IN_PROCESS) {
        ensureDirectory(parent)
        try { Files.createDirectory(root) } catch (_: java.nio.file.FileAlreadyExistsException) { }
        ensureDirectory(root)
        FileChannel.open(root.resolve(LOCK), StandardOpenOption.CREATE, StandardOpenOption.WRITE, NOFOLLOW_LINKS).use {
            it.lock().use { block() }
        }
    }

    private fun ensureDirectory(path: Path) {
        check(Files.isDirectory(path, NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)) { "invalid scratch root" }
    }

    companion object {
        private const val LOCK = ".s208.lock"
        private val OWNED = Regex("s208-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.tmp")
        private val LOCK_IN_PROCESS = Any()
    }
}

package com.deepstudent.services.resources.data

import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

internal fun reapOwned(
    target: Path, quarantine: Path, expected: BlobIdentity, identities: BlobIdentityReader, faults: ResourceFaults,
): Boolean {
    // The open fd pins the inode while another process could replace its pathname.
    FileChannel.open(target, StandardOpenOption.READ, NOFOLLOW_LINKS).use {
        if (identities.read(target) != expected) return false
        faults.hit(ResourceCut.BEFORE_REAP_MOVE, expected.size)
        Files.move(target, quarantine, ATOMIC_MOVE)
        try {
            faults.hit(ResourceCut.AFTER_REAP_MOVE, expected.size)
            val moved = identities.read(quarantine) ?: return false
            if (!expected.sameMovedFile(moved)) return false
            Files.delete(quarantine)
            return true
        } finally {
            // All normal publishers share the publication lock. Never replace a visible target.
            if (Files.exists(quarantine, NOFOLLOW_LINKS) && !Files.exists(target, NOFOLLOW_LINKS)) {
                Files.move(quarantine, target, ATOMIC_MOVE)
            }
        }
    }
}


internal fun matchesBlob(path: Path, hash: String, size: Long): Boolean {
    if (!Files.isRegularFile(path, NOFOLLOW_LINKS) || Files.size(path) != size || size > BlobFiles.MAX_BYTES) return false
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(path, StandardOpenOption.READ, NOFOLLOW_LINKS).use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            if (n > 0) digest.update(buffer, 0, n)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) } == hash
}

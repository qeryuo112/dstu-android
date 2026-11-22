package com.deepstudent.app.backup

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.READ
import java.security.DigestInputStream
import java.security.MessageDigest

/** Retain the exact authenticated ZIP bytes, not an arbitrary replacement URI. */
internal fun copyAuthenticatedBackup(source: ValidatedBackupZip, destination: File, cancel: () -> Unit) {
    val available = (checkNotNull(destination.parentFile).usableSpace - 16L * 1024 * 1024).coerceAtLeast(0)
    if (source.size <= 0 || available < source.size) throw IOException("backup queue capacity")
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(source.file.toPath(), READ, NOFOLLOW_LINKS).use { raw ->
        val input = DigestInputStream(raw, digest)
        FileOutputStream(destination).use { out ->
            copyBounded(input, out, source.size, cancel)
            if (out.channel.size() != source.size || !MessageDigest.isEqual(source.sha256, digest.digest())) {
                throw EncryptedBackupFileException(EncryptedBackupFileError.ZIP_CHANGED)
            }
            out.fd.sync()
        }
    }
}

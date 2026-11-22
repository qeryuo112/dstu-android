package com.deepstudent.platform.security

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes

/** Runs only while the caller holds the master/credential transaction lock. */
internal class MasterKeyProbe(
    private val directory: File,
    private val masterKeys: MasterKeyStore,
) {
    fun requireUsable() {
        val current = masterKeys.loadExisting()
        if (current == null) {
            if (hasCredentialRecords()) throw masterKeyLost()
            return
        }
        try {
            if (current.size != WrappedMasterKeyStore.MASTER_KEY_BYTES) throw masterKeyUnconfirmed()
        } finally {
            current.close()
        }
    }

    fun requireConfirmedLoss() {
        val lost = try {
            requireUsable()
            false
        } catch (error: CredentialStoreException) {
            if (error.error == CredentialStoreError.MASTER_KEY_UNAVAILABLE &&
                error.masterKeyFailure == MasterKeyFailure.PERMANENTLY_LOST
            ) {
                true
            } else {
                throw error
            }
        }
        if (!lost) throw CredentialStoreException(CredentialStoreError.MASTER_KEY_RESET_NOT_ALLOWED)
    }

    fun deleteCredentialRecords() {
        if (!directory.exists()) return
        val files = try {
            SecureFiles.requireDirectory(directory)
            directory.listFiles()
        } catch (_: Exception) {
            throw CredentialStoreException(CredentialStoreError.STORAGE_UNAVAILABLE)
        } ?: throw CredentialStoreException(CredentialStoreError.STORAGE_UNAVAILABLE)
        for (file in files) {
            val record = file.name.endsWith(".dsc") &&
                (file.isFile || SecureFiles.isSymlink(file))
            if (record && !SecureFiles.wipeAndDelete(file)) {
                throw CredentialStoreException(CredentialStoreError.STORAGE_UNAVAILABLE)
            }
        }
    }

    fun hasCredentialRecords(): Boolean {
        val attributes = try {
            Files.readAttributes(directory.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        } catch (_: NoSuchFileException) {
            return false
        } catch (_: Exception) {
            throw masterKeyUnconfirmed()
        }
        if (!attributes.isDirectory) throw masterKeyUnconfirmed()
        return try {
            Files.newDirectoryStream(directory.toPath()).use { files ->
                files.any { it.fileName.toString().endsWith(".dsc") }
            }
        } catch (_: Exception) {
            throw masterKeyUnconfirmed()
        }
    }
}

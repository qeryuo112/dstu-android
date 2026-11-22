package com.deepstudent.platform.security

import java.io.File
import java.io.IOException
import java.security.SecureRandom

interface CredentialStore : AutoCloseable {
    fun put(key: CredentialKey, value: SecretBytes)

    fun get(key: CredentialKey): SecretBytes

    fun delete(key: CredentialKey)
}

/**
 * File-backed credential port. Each record is independently authenticated with
 * the Keystore-wrapped master key and bound to its logical key through GCM AAD.
 * The file name is a digest, so neither names nor values appear in cleartext.
 */
class EncryptedCredentialStore(
    private val directory: File,
    private val masterKeys: MasterKeyStore,
    private val audit: SecurityAudit = SecurityAudit.NONE,
    private val random: SecureRandom = SecureRandom(),
) : CredentialStore {
    private val lock = Any()
    private val cipher = CredentialCipher(random)
    private val masterProbe = MasterKeyProbe(directory, masterKeys)
    private var expectedMasterFingerprint: String? = null
    private var invalidated = false
    private var closed = false

    override fun put(key: CredentialKey, value: SecretBytes) = transaction {
        ensureOpen()
        val digest = digest12(key.value)
        val size = runCatching { value.size }.getOrElse {
            throw CredentialStoreException(CredentialStoreError.INVALID_VALUE)
        }
        audit.record(SecurityOperation.CREDENTIAL_PUT, SecurityOutcome.STARTED, digest, size.toLong())
        try {
            SecureFiles.ensureDirectory(directory)
            SecureFiles.rejectSymlink(recordFile(key))
            val plaintext = value.use { it.copyOf() }
            try {
                val master = masterKey()
                val record = try {
                    master.use { cipher.encrypt(key, plaintext, it) }
                } finally {
                    master.close()
                }
                SecureFiles.writeAtomic(recordFile(key), record)
            } finally {
                plaintext.wipe()
            }
            audit.record(SecurityOperation.CREDENTIAL_PUT, SecurityOutcome.SUCCEEDED, digest, size.toLong())
        } catch (error: CredentialStoreException) {
            audit.record(
                SecurityOperation.CREDENTIAL_PUT,
                SecurityOutcome.FAILED,
                digest,
                size.toLong(),
                error.error.name,
            )
            throw error
        } catch (_: IllegalStateException) {
            audit.record(
                SecurityOperation.CREDENTIAL_PUT,
                SecurityOutcome.FAILED,
                digest,
                size.toLong(),
                CredentialStoreError.INVALID_VALUE.name,
            )
            throw CredentialStoreException(CredentialStoreError.INVALID_VALUE)
        } catch (_: Exception) {
            audit.record(
                SecurityOperation.CREDENTIAL_PUT,
                SecurityOutcome.FAILED,
                digest,
                size.toLong(),
                CredentialStoreError.STORAGE_UNAVAILABLE.name,
            )
            throw CredentialStoreException(CredentialStoreError.STORAGE_UNAVAILABLE)
        }
    }

    override fun get(key: CredentialKey): SecretBytes = transaction {
        ensureOpen()
        val digest = digest12(key.value)
        try {
            SecureFiles.requireDirectory(directory)
        } catch (_: Exception) {
            audit.record(SecurityOperation.CREDENTIAL_GET, SecurityOutcome.NOT_FOUND, digest)
            throw CredentialStoreException(CredentialStoreError.NOT_FOUND)
        }
        val file = recordFile(key)
        if (!file.isFile) {
            audit.record(SecurityOperation.CREDENTIAL_GET, SecurityOutcome.NOT_FOUND, digest)
            throw CredentialStoreException(CredentialStoreError.NOT_FOUND)
        }
        audit.record(SecurityOperation.CREDENTIAL_GET, SecurityOutcome.STARTED, digest)
        try {
            val record = readRecord(file)
            val master = masterKey()
            val plaintext = try {
                master.use { cipher.decrypt(key, record, it) }
            } finally {
                master.close()
            }
            audit.record(
                SecurityOperation.CREDENTIAL_GET,
                SecurityOutcome.SUCCEEDED,
                digest,
                plaintext.size.toLong(),
            )
            SecretBytes.takeOwnership(plaintext)
        } catch (error: CredentialStoreException) {
            audit.record(
                SecurityOperation.CREDENTIAL_GET,
                SecurityOutcome.FAILED,
                digest,
                errorCode = error.error.name,
            )
            throw error
        } catch (_: Exception) {
            audit.record(
                SecurityOperation.CREDENTIAL_GET,
                SecurityOutcome.FAILED,
                digest,
                errorCode = CredentialStoreError.INTEGRITY_FAILURE.name,
            )
            throw CredentialStoreException(CredentialStoreError.INTEGRITY_FAILURE)
        }
    }

    override fun delete(key: CredentialKey) = transaction {
        ensureOpen()
        val digest = digest12(key.value)
        audit.record(SecurityOperation.CREDENTIAL_DELETE, SecurityOutcome.STARTED, digest)
        val file = recordFile(key)
        if (file.exists() || SecureFiles.isSymlink(file)) {
            try {
                SecureFiles.requireDirectory(directory)
            } catch (_: Exception) {
                audit.record(
                    SecurityOperation.CREDENTIAL_DELETE,
                    SecurityOutcome.FAILED,
                    digest,
                    errorCode = CredentialStoreError.STORAGE_UNAVAILABLE.name,
                )
                throw CredentialStoreException(CredentialStoreError.STORAGE_UNAVAILABLE)
            }
        }
        if ((file.exists() || SecureFiles.isSymlink(file)) && !SecureFiles.wipeAndDelete(file)) {
            audit.record(
                SecurityOperation.CREDENTIAL_DELETE,
                SecurityOutcome.FAILED,
                digest,
                errorCode = CredentialStoreError.STORAGE_UNAVAILABLE.name,
            )
            throw CredentialStoreException(CredentialStoreError.STORAGE_UNAVAILABLE)
        }
        audit.record(SecurityOperation.CREDENTIAL_DELETE, SecurityOutcome.SUCCEEDED, digest)
    }

    override fun close() = synchronized(lock) {
        if (!closed) {
            closed = true
        }
    }

    /**
     * Checks the current master without creating or rotating it. A clean install
     * with neither master nor credential records is usable. The only permitted
     * side effect is acquiring the existing cross-process lock bookkeeping.
     */
    fun requireMasterKeyUsable() = transaction {
        masterProbe.requireUsable()
    }

    /**
     * Rechecks under the same cross-process transaction used by the destructive
     * operation. Unconfirmed I/O/provider errors never authorize deletion.
     */
    internal fun rebuildLostMasterKey(trace: SecurityTrace? = null) = transaction {
        masterProbe.requireConfirmedLoss()
        trace?.succeed()
        trace?.start(SecurityPhase.RESET_APPLY)
        rebuildMasterKey()
    }

    /**
     * Deletes all stored credentials, removes the platform wrapping key and
     * creates a fresh wrapped master. Existing records would no longer
     * authenticate, so they are wiped instead of being left behind.
     */
    fun rebuildMasterKey() = transaction {
        ensureOpen()
        val digest = digest12(REBUILD_DOMAIN)
        audit.record(SecurityOperation.KEYSTORE_REBUILD, SecurityOutcome.STARTED, digest)
        invalidated = true
        expectedMasterFingerprint = null
        try {
            masterProbe.deleteCredentialRecords()
            masterKeys.delete()
            val master = masterKeys.loadOrCreate()
            if (master.size != WrappedMasterKeyStore.MASTER_KEY_BYTES) {
                master.close()
                throw CredentialStoreException(CredentialStoreError.MASTER_KEY_UNAVAILABLE)
            }
            master.close()
            expectedMasterFingerprint = fingerprint()
            if (masterKeys.tracksFingerprint() && expectedMasterFingerprint == null) {
                throw CredentialStoreException(CredentialStoreError.MASTER_KEY_UNAVAILABLE)
            }
            invalidated = false
            audit.record(SecurityOperation.KEYSTORE_REBUILD, SecurityOutcome.SUCCEEDED, digest)
        } catch (error: CredentialStoreException) {
            audit.record(
                SecurityOperation.KEYSTORE_REBUILD,
                SecurityOutcome.FAILED,
                digest,
                errorCode = error.error.name,
            )
            throw error
        } catch (_: Exception) {
            audit.record(
                SecurityOperation.KEYSTORE_REBUILD,
                SecurityOutcome.FAILED,
                digest,
                errorCode = CredentialStoreError.MASTER_KEY_UNAVAILABLE.name,
            )
            throw CredentialStoreException(CredentialStoreError.MASTER_KEY_UNAVAILABLE)
        }
    }

    private fun ensureOpen() {
        if (closed) throw CredentialStoreException(CredentialStoreError.CLOSED)
    }

    private fun <T> transaction(block: () -> T): T = synchronized(lock) {
        ensureOpen()
        masterKeys.withExclusiveAccess(block)
    }

    private fun masterKey(): SecretBytes {
        if (invalidated) masterUnavailable()
        val before = checkMasterRevision()
        // No cache: an unchanged wrapped file does not prove Keystore validity.
        val master = try {
            masterKeys.loadOrCreate()
        } catch (error: CredentialStoreException) {
            if (error.masterKeyFailure == MasterKeyFailure.PERMANENTLY_LOST) invalidated = true
            throw CredentialStoreException(
                CredentialStoreError.MASTER_KEY_UNAVAILABLE,
                error.masterKeyFailure ?: MasterKeyFailure.UNCONFIRMED,
            )
        } catch (_: Exception) {
            throw masterKeyUnconfirmed()
        }
        validateMaster(master, before)
        return master
    }

    private fun checkMasterRevision(): String? {
        if (!masterKeys.tracksFingerprint()) return null
        val before = fingerprint()
        if (expectedMasterFingerprint != null) {
            if (before != expectedMasterFingerprint) masterUnavailable()
        } else if (before == null && masterProbe.hasCredentialRecords()) {
            // A null fingerprint alone could mean I/O failure: distinguish it
            // from a genuinely absent master before classifying it as lost.
            masterProbe.requireUsable()
            masterUnavailable()
        }
        return before
    }

    private fun validateMaster(master: SecretBytes, before: String?) {
        val after = fingerprint()
        val wrongLength = master.size != WrappedMasterKeyStore.MASTER_KEY_BYTES
        val changed = masterKeys.tracksFingerprint() && (after == null || (before != null && before != after))
        if (wrongLength || changed) {
            master.close()
            masterUnavailable()
        }
        expectedMasterFingerprint = after
    }

    private fun masterUnavailable(): Nothing {
        invalidated = true
        throw masterKeyUnconfirmed()
    }

    private fun fingerprint(): String? =
        if (masterKeys.tracksFingerprint()) masterKeys.fingerprint() else null

    private fun readRecord(file: File): ByteArray = try {
        SecureFiles.readBounded(file, MAX_RECORD_BYTES)
    } catch (_: IOException) {
        throw CredentialStoreException(CredentialStoreError.STORAGE_UNAVAILABLE)
    }

    private fun recordFile(key: CredentialKey): File =
        File(directory, digestFull(FILE_DOMAIN + key.value) + RECORD_SUFFIX)

    companion object {
        const val MAX_RECORD_BYTES = SecretBytes.MAX_SIZE_BYTES + 128
        private const val RECORD_SUFFIX = ".dsc"
        private const val FILE_DOMAIN = "deepstudent.credential.file.v1\u0000"
        private const val REBUILD_DOMAIN = "deepstudent.credential.rebuild.v1"
    }
}

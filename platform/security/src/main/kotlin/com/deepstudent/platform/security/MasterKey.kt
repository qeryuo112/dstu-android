package com.deepstudent.platform.security

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Wraps a data-encryption key with a key that is owned by the platform. The
 * wrapper must never persist or log the plaintext key.
 */
interface KeyWrapper {
    fun wrap(plaintext: ByteArray): ByteArray

    fun unwrap(wrapped: ByteArray): ByteArray

    fun deleteKey() {
        // Optional for wrappers whose key lifecycle is managed externally.
    }
}

interface MasterKeyStore {
    fun loadOrCreate(): SecretBytes

    /** Returns null only for a confirmed absent master; never creates one. */
    fun loadExisting(): SecretBytes? = throw masterKeyUnconfirmed()

    fun delete()

    /** Serializes credential mutation and master rotation across instances/processes. */
    fun <T> withExclusiveAccess(block: () -> T): T = block()

    /**
     * Non-secret revision of the persisted wrapped master. Consumers that cache
     * a loaded master use it to detect external deletion/replacement and fail
     * closed instead of writing records with a stale key.
     */
    fun tracksFingerprint(): Boolean = false

    /** Null when the wrapped master is missing, unreadable or a symlink. */
    fun fingerprint(): String? = null
}

/**
 * Stores a random 32-byte master key wrapped by [KeyWrapper]. An existing
 * wrapped file is never replaced when unwrapping fails: losing the wrapping
 * key is a fail-closed condition, not an invitation to rotate silently.
 *
 * Creation is serialized by a process-wide lock plus an OS file lock on a
 * sibling `.lock` file, so two store instances (or two processes) cannot
 * generate competing masters and leave one writer with a stale key.
 */
class WrappedMasterKeyStore(
    private val file: File,
    private val wrapper: KeyWrapper,
    private val random: SecureRandom = SecureRandom(),
    private val audit: SecurityAudit = SecurityAudit.NONE,
) : MasterKeyStore {
    override fun loadOrCreate(): SecretBytes {
        val digest = fileDigest()
        audit.record(SecurityOperation.KEYSTORE_OPEN, SecurityOutcome.STARTED, digest)
        try {
            val master = withExclusiveAccess {
                loadExistingUnlocked() ?: createNew()
            }
            audit.record(
                SecurityOperation.KEYSTORE_OPEN,
                SecurityOutcome.SUCCEEDED,
                digest,
                master.size.toLong(),
            )
            return master
        } catch (error: CredentialStoreException) {
            audit.record(
                SecurityOperation.KEYSTORE_OPEN,
                SecurityOutcome.FAILED,
                digest,
                errorCode = error.error.name,
            )
            throw error
        } catch (_: Exception) {
            audit.record(
                SecurityOperation.KEYSTORE_OPEN,
                SecurityOutcome.FAILED,
                digest,
                errorCode = CredentialStoreError.MASTER_KEY_UNAVAILABLE.name,
            )
            throw CredentialStoreException(CredentialStoreError.MASTER_KEY_UNAVAILABLE)
        }
    }

    override fun loadExisting(): SecretBytes? = withExclusiveAccess {
        loadExistingUnlocked()
    }

    private fun loadExistingUnlocked(): SecretBytes? {
        val attributes = try {
            Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        } catch (_: NoSuchFileException) {
            return null
        } catch (_: Exception) {
            throw masterKeyUnconfirmed()
        }
        if (!attributes.isRegularFile) throw masterKeyUnconfirmed()
        return unwrapExisting()
    }

    override fun tracksFingerprint(): Boolean = true

    override fun fingerprint(): String? = try {
        if (!file.isFile || SecureFiles.isSymlink(file)) {
            null
        } else {
            digestBytes(SecureFiles.readBounded(file, MAX_WRAPPED_BYTES + HEADER_BYTES))
        }
    } catch (_: Exception) {
        null
    }

    override fun delete() {
        val digest = fileDigest()
        audit.record(SecurityOperation.STAGING_CLEANUP, SecurityOutcome.STARTED, digest)
        try {
            withExclusiveAccess {
                wrapper.deleteKey()
                if (!SecureFiles.deleteBestEffort(file)) {
                    throw IOException("wrapped key file is unavailable")
                }
            }
            audit.record(SecurityOperation.STAGING_CLEANUP, SecurityOutcome.SUCCEEDED, digest)
        } catch (_: Exception) {
            audit.record(
                SecurityOperation.STAGING_CLEANUP,
                SecurityOutcome.FAILED,
                digest,
                errorCode = CredentialStoreError.MASTER_KEY_UNAVAILABLE.name,
            )
            throw CredentialStoreException(CredentialStoreError.MASTER_KEY_UNAVAILABLE)
        }
    }

    override fun <T> withExclusiveAccess(block: () -> T): T {
        val parent = file.absoluteFile.parentFile
            ?: throw CredentialStoreException(CredentialStoreError.MASTER_KEY_UNAVAILABLE)
        return try {
            SecureFiles.ensureDirectory(parent)
            SecureFileMutex.withLock(File(parent, file.name + LOCK_SUFFIX), block)
        } catch (error: CredentialStoreException) {
            throw error
        } catch (_: Exception) {
            throw CredentialStoreException(CredentialStoreError.MASTER_KEY_UNAVAILABLE)
        }
    }

    private fun createNew(): SecretBytes {
        val master = ByteArray(MASTER_KEY_BYTES)
        random.nextBytes(master)
        try {
            val wrapped = wrapper.wrap(master)
            if (wrapped.isEmpty() || wrapped.size > MAX_WRAPPED_BYTES) {
                throw CredentialStoreException(CredentialStoreError.MASTER_KEY_UNAVAILABLE)
            }
            val payload = ByteArray(HEADER_BYTES + wrapped.size)
            val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
            buffer.put(MAGIC)
            buffer.put(VERSION)
            buffer.putInt(wrapped.size)
            buffer.put(wrapped)
            SecureFiles.writeAtomic(file, payload)
            return SecretBytes.takeOwnership(master)
        } catch (error: Exception) {
            master.wipe()
            if (error is CredentialStoreException) throw error
            throw CredentialStoreException(CredentialStoreError.MASTER_KEY_UNAVAILABLE)
        }
    }

    private fun unwrapExisting(): SecretBytes {
        val payload = try {
            SecureFiles.readBounded(file, MAX_WRAPPED_BYTES + HEADER_BYTES)
        } catch (_: IOException) {
            throw masterKeyUnconfirmed()
        }
        val wrapped = parseWrapped(payload)
        val master = unwrapWithClassification(wrapped)
        if (master.size != MASTER_KEY_BYTES) {
            master.wipe()
            throw masterKeyUnconfirmed()
        }
        return SecretBytes.takeOwnership(master)
    }

    private fun parseWrapped(payload: ByteArray): ByteArray {
        if (payload.size < HEADER_BYTES) {
            throw masterKeyUnconfirmed()
        }
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        val magic = ByteArray(MAGIC.size)
        buffer.get(magic)
        if (!magic.contentEquals(MAGIC) || buffer.get() != VERSION) {
            throw masterKeyUnconfirmed()
        }
        val wrappedLength = buffer.int
        if (wrappedLength <= 0 || wrappedLength != buffer.remaining()) {
            throw masterKeyUnconfirmed()
        }
        val wrapped = ByteArray(wrappedLength)
        buffer.get(wrapped)
        return wrapped
    }

    private fun unwrapWithClassification(wrapped: ByteArray): ByteArray = try {
        wrapper.unwrap(wrapped)
    } catch (error: CredentialStoreException) {
        if (error.error == CredentialStoreError.MASTER_KEY_UNAVAILABLE) {
            throw CredentialStoreException(
                CredentialStoreError.MASTER_KEY_UNAVAILABLE,
                error.masterKeyFailure ?: MasterKeyFailure.UNCONFIRMED,
            )
        }
        throw masterKeyUnconfirmed()
    } catch (_: Exception) {
        throw masterKeyUnconfirmed()
    }

    private fun fileDigest(): String = digest12(file.name)

    companion object {
        const val MASTER_KEY_BYTES = 32
        const val MAX_WRAPPED_BYTES = 4096
        private const val HEADER_BYTES = 9
        private const val VERSION: Byte = 1
        private const val LOCK_SUFFIX = ".lock"
        private val MAGIC = byteArrayOf(0x44, 0x53, 0x4d, 0x4b)
    }
}

internal fun digest12(value: String): String {
    return digestHex(value).take(12)
}

internal fun digestFull(value: String): String {
    return digestHex(value)
}

internal fun digestBytes(value: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(value)
    return digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

private fun digestHex(value: String): String {
    return digestBytes(value.toByteArray(Charsets.UTF_8))
}

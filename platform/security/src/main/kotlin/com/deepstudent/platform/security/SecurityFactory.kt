package com.deepstudent.platform.security

import android.content.Context
import java.io.File

/**
 * Process-owned security ports. The application composition root creates one
 * instance and injects the narrow ports into features/services; it is not a
 * service locator and owns no business state.
 */
class SecurityPorts internal constructor(
    val credentials: CredentialStore,
    private val credentialStore: EncryptedCredentialStore,
    val dsbk: DsbkCrypto,
    val staging: DsbkStagingArea,
    private val audit: SecurityAudit,
) : AutoCloseable {
    private val lock = Any()
    private var closed = false

    /**
     * Checks the master without creating or rotating it. A fresh install is
     * usable. On MASTER_KEY_UNAVAILABLE inspect `masterKeyFailure`; only
     * PERMANENTLY_LOST offers a destructive re-pair action.
     */
    fun requireMasterKeyUsable() = synchronized(lock) {
        val trace = SecurityTrace(audit, SecurityOperation.MASTER_KEY_PROBE)
        trace.start(SecurityPhase.KEY_PROBE)
        try {
            if (closed) throw CredentialStoreException(CredentialStoreError.CLOSED)
            credentialStore.requireMasterKeyUsable()
            trace.succeed()
        } catch (error: CredentialStoreException) {
            trace.fail(error)
            throw error
        }
    }

    /**
     * Revalidates inside the credential transaction and resets only a positively
     * confirmed permanent loss. A healthy master returns RESET_NOT_ALLOWED;
     * unconfirmed failures remain MASTER_KEY_UNAVAILABLE / UNCONFIRMED.
     */
    fun rebuildLostMasterKey() = synchronized(lock) {
        val trace = SecurityTrace(audit, SecurityOperation.MASTER_KEY_RESET)
        trace.start(SecurityPhase.RESET_RECHECK)
        try {
            if (closed) throw CredentialStoreException(CredentialStoreError.CLOSED)
            credentialStore.rebuildLostMasterKey(trace)
            trace.succeed()
        } catch (error: CredentialStoreException) {
            trace.fail(error)
            throw error
        }
    }

    /**
     * Destructive platform-key rotation: wipes stored credentials, deletes the
     * AndroidKeystore alias and creates a fresh wrapped master. Only the
     * composition root should call this after an explicit user decision.
     */
    fun rebuildMasterKey() = synchronized(lock) {
        if (closed) throw CredentialStoreException(CredentialStoreError.CLOSED)
        credentialStore.rebuildMasterKey()
    }

    override fun close() = synchronized(lock) {
        if (closed) return
        closed = true
        try {
            staging.close()
        } finally {
            credentials.close()
        }
    }
}

/**
 * Explicit production factory for S1.23. Main-thread integration:
 *
 * `val security = SecurityFactory.create(context)` (or the [File] overload for
 * tests) and pass `security.credentials`, `security.dsbk` and
 * `security.staging` into the owning service. Call [SecurityPorts.close] only
 * when the process composition is torn down; staging recovery runs at create.
 */
object SecurityFactory {
    const val DEFAULT_STAGING_BYTES: Long = 1024L * 1024L * 1024L
    private const val MASTER_FILE = "master.key"
    private const val CREDENTIALS_DIR = "credentials"

    fun create(
        rootDirectory: File,
        wrapper: KeyWrapper,
        stagingDirectory: File = File(rootDirectory, "staging"),
        maxStagedBytes: Long = DEFAULT_STAGING_BYTES,
        audit: SecurityAudit = SecurityAudit.NONE,
    ): SecurityPorts {
        try {
            SecureFiles.ensureDirectory(rootDirectory)
        } catch (_: Exception) {
            throw CredentialStoreException(CredentialStoreError.STORAGE_UNAVAILABLE)
        }
        val masterKeys = WrappedMasterKeyStore(
            File(rootDirectory, MASTER_FILE),
            wrapper,
            audit = audit,
        )
        val staging = try {
            DsbkStagingArea(stagingDirectory, maxStagedBytes)
        } catch (_: Exception) {
            throw CredentialStoreException(CredentialStoreError.STORAGE_UNAVAILABLE)
        }
        staging.cleanupOrphans()
        val credentials = EncryptedCredentialStore(
            File(rootDirectory, CREDENTIALS_DIR),
            masterKeys,
            audit,
        )
        return SecurityPorts(
            credentials = credentials,
            credentialStore = credentials,
            dsbk = DsbkCrypto.production(audit),
            staging = staging,
            audit = audit,
        )
    }

    fun create(
        rootDirectory: File,
        keystoreAlias: String = AndroidKeystoreKeyWrapper.DEFAULT_ALIAS,
        stagingDirectory: File = File(rootDirectory, "staging"),
        maxStagedBytes: Long = DEFAULT_STAGING_BYTES,
        audit: SecurityAudit = SecurityAudit.NONE,
    ): SecurityPorts = create(
        rootDirectory,
        AndroidKeystoreKeyWrapper(keystoreAlias),
        stagingDirectory,
        maxStagedBytes,
        audit,
    )

    fun create(
        context: Context,
        audit: SecurityAudit = SecurityAudit.NONE,
    ): SecurityPorts = create(
        rootDirectory = File(context.noBackupFilesDir.canonicalFile, "security"),
        keystoreAlias = AndroidKeystoreKeyWrapper.DEFAULT_ALIAS,
        stagingDirectory = File(context.cacheDir.canonicalFile, "security-staging"),
        audit = audit,
    )
}

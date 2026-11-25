package com.deepstudent.app.backup

import android.content.Context
import android.util.Log
import com.deepstudent.app.security.MainSecurityResources
import com.deepstudent.app.storage.AndroidOperationLogger
import com.deepstudent.platform.database.BundledMigrationSource
import com.deepstudent.platform.database.DatabaseRegistry
import com.deepstudent.platform.database.SharedDatabase
import com.deepstudent.platform.security.SecurityPorts
import com.deepstudent.services.backup.data.BackupIdentityAuthorization
import com.deepstudent.services.backup.data.BackupIdentityContinuityCommit
import com.deepstudent.services.backup.data.BackupIdentityLog
import com.deepstudent.services.backup.data.BackupIdentityStore
import com.deepstudent.services.backup.data.RestoreCandidateVerifier
import com.deepstudent.services.sync.api.SyncConfiguration
import com.deepstudent.services.sync.backend.ContinuityClaimAttemptStatus
import com.deepstudent.services.sync.backend.ContinuityClaimConditionalCreateProof
import com.deepstudent.services.sync.backend.ContinuityClaimException
import com.deepstudent.services.sync.backend.ContinuityClaimFailure
import com.deepstudent.services.sync.backend.ContinuityClaimRequest
import com.deepstudent.services.sync.backend.ContinuityClaimRetirementProof
import com.deepstudent.services.sync.backend.ContinuityRemoteClaim
import com.deepstudent.services.sync.backend.EncryptionExpectation
import com.deepstudent.services.sync.backend.ObjectProvider
import com.deepstudent.services.sync.backend.ProviderFactory
import com.deepstudent.services.sync.backend.RepositoryEncryption
import com.deepstudent.services.sync.backend.SyncPayloadCodec
import com.deepstudent.services.sync.backend.transfer.ContinuityWriterReadback
import com.deepstudent.services.sync.data.ContinuitySyncBinding
import com.deepstudent.services.sync.data.LocalSyncStore
import com.deepstudent.services.sync.data.WebDavProviderFactory
import com.deepstudent.services.sync.data.transfer.DsbkSyncCryptography
import com.deepstudent.services.sync.data.transfer.StoredEncryptionPassword
import com.deepstudent.services.sync.data.transfer.StoredWebDavProvider
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.time.Instant

/** Composition of independently pinned management proof, real paired repository and the existing writer store. */
internal class ManagedContinuityRestore(
    private val context: Context,
    private val openSecurity: () -> SecurityPorts = { MainSecurityResources.open(context) },
    private val providers: ProviderFactory = WebDavProviderFactory(),
    private val clock: () -> Instant = Instant::now,
) {
    fun now(): Instant = clock()

    fun paired(): PairedContinuityRepository = openSecurity().use { ports ->
        val current = pairedConfig(store(ports))
        PairedContinuityRepository(ContinuitySyncBinding.repositoryUrl(current), current.instanceId)
    }

    /** Called only from the cold I/O boundary, before the maintenance/SQLite locks are acquired. */
    fun claim(verified: VerifiedContinuityRestore, checkPending: () -> Boolean, beforeCreate: () -> Unit) = openSecurity().use { ports ->
        runBlocking { withTimeout(90_000) { claimPaired(ports, verified, checkPending, beforeCreate) } }
    }

    private suspend fun claimPaired(
        ports: SecurityPorts,
        verified: VerifiedContinuityRestore,
        checkPending: () -> Boolean,
        beforeCreate: () -> Unit,
    ) {
        val store = store(ports)
        val actual = pairedConfig(store)
        requirePairedGrant(actual, verified)
        val provider = StoredWebDavProvider(store, providers).open(actual)
        val encryption = RepositoryEncryption(DsbkSyncCryptography(ports.dsbk))
        StoredEncryptionPassword(store).use(actual) { password ->
            val policy = encryption.check(provider, actual.encrypted, password)
            encryption.codec(password).use { codec ->
                claimAuthorized(provider, verified, policy, codec, checkPending, beforeCreate)
            }
        }
    }

    private fun requirePairedGrant(actual: SyncConfiguration, verified: VerifiedContinuityRestore) {
        val grant = verified.grant
        if (ContinuitySyncBinding.repositoryUrl(actual) != grant.repositoryUrl || actual.instanceId != grant.repository.instanceId ||
            actual.deviceId == grant.writerId
        ) {
            failContinuity(ContinuityRestoreError.REPOSITORY_CHANGED)
        }
    }

    private suspend fun claimAuthorized(
        provider: ObjectProvider,
        verified: VerifiedContinuityRestore,
        policy: EncryptionExpectation,
        codec: SyncPayloadCodec,
        checkPending: () -> Boolean,
        beforeCreate: () -> Unit,
    ) {
        val grant = verified.grant
        val request = ContinuityClaimRequest(
            grant.repository.instanceId,
            grant.writerId,
            grant.targetInstallationId,
            grant.operationId,
            grant.archiveSha256,
            grant.sidecarSha256,
        )
        val claim = ContinuityRemoteClaim(
            provider,
            // The independently verified grant affirms the managed namespace's atomicity and protection;
            // an ordinary WebDAV response is not enough to assume these guarantees.
            conditionalCreate = { remote, instance ->
                grant.bind(verified.matched, now())
                if (remote === provider && instance == grant.repository.instanceId) {
                    ContinuityClaimConditionalCreateProof(provider, instance)
                } else {
                    null
                }
            },
            retirement = { observed ->
                grant.bind(verified.matched, now())
                policy.requireUnchanged(provider)
                requireSequence(provider, codec, verified)
                ContinuityClaimRetirementProof(observed)
            },
            ledger = {
                if (it == request && checkPending()) {
                    ContinuityClaimAttemptStatus.PENDING
                } else {
                    ContinuityClaimAttemptStatus.MISSING_OR_MISMATCHED
                }
            },
            logger = AndroidOperationLogger,
            beforeConditionalCreate = { observed ->
                check(observed == request)
                beforeCreate()
            },
        )
        check(claim.claim(request).request == request)
    }

    private suspend fun requireSequence(provider: ObjectProvider, codec: SyncPayloadCodec, verified: VerifiedContinuityRestore) {
        if (ContinuityWriterReadback.sequence(provider, codec, verified.grant.writerId) != verified.grant.uploadSeq) {
            throw ContinuityClaimException(ContinuityClaimFailure.WRITER_SEQUENCE_CHANGED)
        }
    }

    fun stage(
        base: RestoreCandidateVerifier,
        verified: VerifiedContinuityRestore,
        authorization: BackupIdentityAuthorization,
    ): RestoreCandidateVerifier = object : RestoreCandidateVerifier by base {
        override fun verifyDatabases(candidate: File) {
            base.verifyDatabases(candidate)
            verified.grant.bind(verified.matched, now())
            withVfs(candidate, verified.grant.operationId) { store -> store.stageAuthorizedContinuity(verified.matched, authorization) }
        }

        override fun verifyResources(candidate: File) {
            base.verifyResources(candidate)
            verified.grant.bind(verified.matched, now())
        }
    }

    /** Rebind only the exact installation whose SQLite commit is read from the actual active slot. */
    fun finish(active: File, expected: BackupIdentityContinuityCommit, verified: VerifiedContinuityRestore) {
        withVfs(active, verified.grant.operationId) { it.requireAuthorizedContinuityCommit(expected) }
        openSecurity().use { ports ->
            val grant = verified.grant
            store(ports).adoptWriterForContinuity(
                ContinuitySyncBinding(
                    grant.operationId,
                    grant.writerId,
                    grant.repository.instanceId,
                    grant.repositoryUrl,
                ),
            )
        }
    }

    private fun <T> withVfs(root: File, operationId: String, action: (BackupIdentityStore) -> T): T = DatabaseRegistry.openShared(
        root.resolve("databases/vfs.db"),
        SharedDatabase.VFS,
        BundledMigrationSource(context),
    ).use { action(BackupIdentityStore(it, identityLog(operationId))) }

    private fun store(ports: SecurityPorts) = LocalSyncStore(context.noBackupFilesDir.canonicalFile.resolve("sync"), ports.credentials)

    private fun pairedConfig(store: LocalSyncStore): SyncConfiguration {
        val current = store.read() ?: failContinuity(ContinuityRestoreError.NOT_PAIRED)
        store.verifyCredentialReferences(current)
        return current
    }
}

private fun identityLog(operationId: String) = BackupIdentityLog { event ->
    Log.i(
        "DstuRestoreIdentity",
        "event=backup.continuity owner=${event.owner} correlationId=$operationId " +
            "phase=${event.phase} state=${event.status} elapsedMs=${event.elapsedMillis} code=${event.diagnostic ?: "none"}",
    )
}

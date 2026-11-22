package com.deepstudent.services.backup.data

import com.deepstudent.platform.database.SharedDatabase
import com.deepstudent.platform.database.SqlDatabase
import com.deepstudent.services.backup.backend.ContinuityValidation
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Scoped to the same already-migrated VFS database as the candidate restore slot. This class does
 * not select/activate a slot, issue writer IDs, pair a repository, or create a remote claim. The
 * caller must bind this database to the exact authenticated archive represented by the validated sidecar.
 */
class BackupIdentityStore(
    private val candidateVfs: SqlDatabase,
    private val log: BackupIdentityLog = BackupIdentityLog.NONE,
) {
    /** Clear consumption/binding baselines without touching upload history or pending publications. */
    fun resetForOrdinaryRestore(): BackupIdentityRePairRequired = traced(BackupIdentityMode.ORDINARY) {
        requireVfs()
        candidateVfs.transaction {
            backupIdentitySetup()
            backupIdentityResetConsumption()
        }
        BackupIdentityRePairRequired
    }

    /** No default production claim: unavailable or untrusted fencing must fail before local writes. */
    fun restoreContinuity(
        matched: ContinuityValidation.Matched,
        claim: BackupIdentityClaimPort = BackupIdentityClaimPort.UNAVAILABLE,
    ): Unit = traced(BackupIdentityMode.CONTINUITY) {
        requireVfs()
        val active = AtomicBoolean(true)
        val invoked = AtomicBoolean(false)
        val succeeded = AtomicBoolean(false)
        val owner = Thread.currentThread()
        val binding = BackupIdentityClaimBinding(matched)
        try {
            claim.withVerifiedExclusiveClaim(binding) { aliases ->
                if (Thread.currentThread() !== owner || !active.get() || !invoked.compareAndSet(false, true)) {
                    throw BackupIdentityException(BackupIdentityFailure.CLAIM_PROTOCOL)
                }
                candidateVfs.transaction {
                    backupIdentitySetup()
                    backupIdentityApplyContinuity(matched, aliases)
                    claim.completePendingInTransaction(binding, this)
                }
                succeeded.set(true)
            }
            if (!succeeded.get()) throw BackupIdentityException(BackupIdentityFailure.CLAIM_PROTOCOL)
        } catch (failure: Throwable) {
            // A port which fails after the database callback may have committed. Never report this
            // as a rollback or authorize slot activation; reconcile the receipt and remote claim.
            if (succeeded.get()) throw BackupIdentityException(BackupIdentityFailure.CLAIM_COMPLETION_UNKNOWN)
            throw failure
        } finally {
            active.set(false)
        }
    }

    /**
     * Applies an already independently authorized and remotely claimed snapshot to this candidate.
     * Always executes the transaction; pre-existing archive receipts/completion rows reject rather
     * than short-circuit. The caller must persist [BackupIdentityAuthorization.expected] outside the
     * archive before calling, and must preserve the remote fence through activation.
     */
    fun stageAuthorizedContinuity(
        matched: ContinuityValidation.Matched,
        authorization: BackupIdentityAuthorization,
    ): BackupIdentityContinuityCommit = traced(BackupIdentityMode.CONTINUITY, authorization.expected.attempt.operationId) {
        requireVfs()
        candidateVfs.transaction {
            backupIdentityValidateAuthorization(matched, authorization)
            backupIdentitySetup()
            backupIdentityCheckNoCommit(authorization.expected)
            backupIdentityApplyContinuity(matched, authorization.aliases)
            backupIdentityRequireAppliedState(matched, authorization.aliases)
            backupIdentityWriteCommit(authorization.expected)
            // Fail and roll back the complete transaction if the marker or receipt cannot be read.
            backupIdentityRequireCommit(authorization.expected)
        }
        authorization.expected
    }

    /**
     * Read-only cold-restart reconciliation. [expected] must come from trusted storage OUTSIDE the
     * restored archive. This verifies the exact operation/target and both receipts; the caller must
     * independently establish that this database is the currently activated slot. Never use this
     * method to bypass an initial staging transaction or to create a missing receipt.
     */
    fun requireAuthorizedContinuityCommit(expected: BackupIdentityContinuityCommit) {
        requireVfs()
        backupIdentityValidateExpected(expected)
        traced(BackupIdentityMode.CONTINUITY, expected.attempt.operationId, BackupIdentityStatus.VERIFIED) {
            candidateVfs.readSnapshot {
                backupIdentityValidateCommitSchema()
                backupIdentityRequireCommit(expected)
            }
        }
    }

    /** Available after either completed identity transaction; reads one coherent SQLite snapshot. */
    fun readSnapshot(instanceId: String, writerId: String): BackupIdentitySnapshot {
        requireVfs()
        return candidateVfs.readSnapshot { backupIdentitySnapshot(instanceId, writerId) }
    }

    private fun requireVfs() {
        if (candidateVfs.migrationReport?.database != SharedDatabase.VFS) {
            throw BackupIdentityException(BackupIdentityFailure.NOT_VFS)
        }
    }

    private fun <T> traced(
        mode: BackupIdentityMode,
        correlationId: String = UUID.randomUUID().toString(),
        successStatus: BackupIdentityStatus = BackupIdentityStatus.COMMITTED,
        action: () -> T,
    ): T {
        val id = correlationId
        val start = System.nanoTime()
        fun event(status: BackupIdentityStatus, diagnostic: BackupIdentityFailure? = null) {
            val elapsed = (System.nanoTime() - start) / 1_000_000
            // A broken observer must not turn a successful commit into an apparent failure.
            try { log.onEvent(BackupIdentityLogEvent(id, mode, status, elapsed, diagnostic)) } catch (_: Exception) { }
        }
        event(BackupIdentityStatus.STARTED)
        return try {
            val result = action()
            event(successStatus)
            result
        } catch (failure: Throwable) {
            event(BackupIdentityStatus.FAILED, (failure as? BackupIdentityException)?.diagnostic)
            throw failure
        }
    }
}

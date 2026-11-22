package com.deepstudent.app.backup

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import com.deepstudent.core.common.id.Digests
import com.deepstudent.platform.backupzip.CheckedZipArchive
import com.deepstudent.platform.database.BundledMigrationSource
import com.deepstudent.platform.storage.FileDataSpace
import com.deepstudent.platform.storage.StagingStatus
import com.deepstudent.services.backup.data.BackupIdentityAuthorization
import com.deepstudent.services.backup.data.BackupIdentityTrustedComposition
import com.deepstudent.services.backup.data.RestoreExecutionResult
import com.deepstudent.services.backup.data.RestoreExecutor
import com.deepstudent.services.sync.backend.ContinuityClaimException
import com.deepstudent.services.sync.backend.ContinuityClaimFailure
import java.io.File
import java.nio.file.Files
import java.time.Instant

/** Uses the ordinary restore journal and executor, but never executes the ordinary identity path. */
internal class ContinuityQueuedRestore(private val context: Context, private val managed: ManagedContinuityRestore) {
    private val journal = ContinuityRestoreJournal(context.noBackupFilesDir.canonicalFile.resolve("backup-continuity"))

    fun issueTarget(renew: Boolean) = if (renew) journal.renewTarget() else journal.issueTarget()
    fun cleanEvidence() {
        journal.cleanEvidence()
        Files.newDirectoryStream(context.cacheDir.canonicalFile.toPath(), "continuity-preview-*.zip").use { paths ->
            paths.forEach(Files::deleteIfExists)
        }
    }

    fun review(source: Uri, files: ContinuityFiles, cancel: () -> Unit): ContinuityReview {
        val scratch = Files.createTempFile(context.cacheDir.canonicalFile.toPath(), "continuity-preview-", ".zip").toFile()
        try {
            copyBackupSource(context, source, scratch, cancel)
            val verified = validate(scratch, files, cancel)
            return ContinuityReview(verified.archive.plan, verified.identity.attempt, evidenceDigest(files), verified.identity.grant.uploadSeq)
        } finally { Files.deleteIfExists(scratch.toPath()) }
    }

    fun queue(
        queue: PendingBackupRestore, source: Uri, files: ContinuityFiles, reviewed: ContinuityReview, cancel: () -> Unit,
    ): BackupRestoreUiStatus {
        queue.prepare()
        try {
            copyBackupSource(context, source, queue.scratch, cancel, durable = true)
            val verified = validate(queue.scratch, files, cancel)
            if (verified.identity.attempt != reviewed.attempt || evidenceDigest(files) != reviewed.evidenceSha256 ||
                verified.archive.plan.manifestSha256 != reviewed.plan.manifestSha256) return BackupRestoreUiStatus.PACK_CHANGED
            journal.requireSameClaimedEvidence(reviewed.evidenceSha256)
            cancel()
            journal.persist(verified.stored)
            queue.publish(BackupRestoreRequest(verified.identity.grant.operationId,
                checkNotNull(verified.archive.plan.manifestSha256), continuity = true))
            return BackupRestoreUiStatus.QUEUED
        } finally { Files.deleteIfExists(queue.scratch.toPath()) }
    }

    fun recover(space: FileDataSpace, queue: PendingBackupRestore, request: BackupRestoreRequest): BackupRestoreUiStatus {
        val trace = ContinuityRestoreTrace(request.id)
        try {
            val stored = journal.load(request.id)
            val alreadyActivated = isActiveAttempt(space, request.id)
            val possibleClaim = journal.mightHaveClaim(request.id, evidenceDigest(stored.files))
            val validated = try {
                val archive = inspectContinuityArchive(queue.input) { }
                check(archive.plan.manifestSha256 == request.manifestHash)
                val checkpoint = if (alreadyActivated) checkNotNull(journal.claimed()) else null
                val verified = verified(queue.input, archive, stored, checkpoint)
                ColdContinuityInput(archive, verified, checkpoint)
            } catch (error: Exception) {
                trace.log("authority", "failed", continuityCode(error))
                return if (!alreadyActivated && !possibleClaim) BackupRestoreUiStatus.CONTINUITY_FAILED
                else BackupRestoreUiStatus.NEEDS_REVIEW
            }
            // An expired grant never starts a new takeover. An already committed slot needs its exact
            // prior authenticated claim time AND unpredictable SQLite commit witness to finish publication.
            trace.log("authority", "verified")
            if (alreadyActivated) return finish(space, validated.identity, checkNotNull(validated.checkpoint), trace)
            return claimAndActivate(space, queue, validated.archive, validated.identity, stored, trace)
        } catch (error: Exception) {
            trace.log("startup", "failed", continuityCode(error))
            return BackupRestoreUiStatus.NEEDS_REVIEW
        }
    }

    private fun isActiveAttempt(space: FileDataSpace, operationId: String): Boolean = space.withMaintenance {
        when (prepare(operationId).status) {
            StagingStatus.ACTIVE -> true
            StagingStatus.WRITABLE -> false
            StagingStatus.PREVIOUSLY_ACTIVATED -> error("continuity slot was superseded")
        }
    }

    private fun claimAndActivate(
        space: FileDataSpace, queue: PendingBackupRestore, archive: ContinuityArchive,
        verified: VerifiedContinuityRestore, stored: StoredContinuityEvidence, trace: ContinuityRestoreTrace,
    ): BackupRestoreUiStatus {
        val request = verified.attempt
        val digest = evidenceDigest(stored.files)
        val priorPossibleClaim = journal.mightHaveClaim(request.operationId, digest)
        val prepared = try {
            prepareClaim(queue, verified, stored, trace)
        } catch (error: Exception) {
            trace.log("claim", "failed", continuityCode(error))
            return claimFailure(error, request.operationId, digest, priorPossibleClaim)
        }
        val verifier = GovernedRestoreVerifier(BundledMigrationSource(context), archive.plan,
            LegacyRestoreChecks(archive.plan, request.operationId))
        val executor = RestoreExecutor(managed.stage(verifier, verified, prepared.second), audit = AndroidRestoreAudit)
        return when (executor.execute(space, request.operationId, archive.plan) { CheckedZipArchive(queue.input) }) {
            is RestoreExecutionResult.Committed -> finish(space, verified, prepared.first, trace)
            // A remote claim is already confirmed: even a deterministic candidate failure
            // must retain the exact operation, evidence and checkpoint for reconciliation.
            is RestoreExecutionResult.Failed -> BackupRestoreUiStatus.NEEDS_REVIEW
            else -> BackupRestoreUiStatus.NEEDS_REVIEW
        }
    }

    private fun claimFailure(error: Exception, id: String, digest: String, priorPossibleClaim: Boolean): BackupRestoreUiStatus {
        val reason = (error as? ContinuityClaimException)?.reason
        // A first attempt that read back another claimant is a proven rejection, not our write.
        if (reason == ContinuityClaimFailure.ALREADY_CLAIMED && !priorPossibleClaim && journal.claimed() == null) {
            return BackupRestoreUiStatus.CONTINUITY_FAILED
        }
        if (journal.mightHaveClaim(id, digest) || error is java.util.concurrent.CancellationException) {
            return BackupRestoreUiStatus.NEEDS_REVIEW
        }
        if (reason == null || reason in terminalClaimRejections) return BackupRestoreUiStatus.CONTINUITY_FAILED
        return BackupRestoreUiStatus.NEEDS_REVIEW
    }

    private fun prepareClaim(
        queue: PendingBackupRestore, verified: VerifiedContinuityRestore,
        stored: StoredContinuityEvidence, trace: ContinuityRestoreTrace,
    ): Pair<ContinuityClaimCheckpoint, BackupIdentityAuthorization> {
        val request = verified.attempt
        if (journal.isCompleted(request.operationId)) failContinuity(ContinuityRestoreError.ALREADY_USED)
        val digest = evidenceDigest(stored.files)
        managed.claim(verified,
            checkPending = { queue.request()?.let { it.id == request.operationId && it.continuity } == true },
            beforeCreate = {
                journal.markClaimIntent(request.operationId, digest)
                trace.log("claim_intent", "persisted")
            })
        val now = managed.now()
        val aliases = verified.grant.bind(verified.matched, now)
        val checkpoint = journal.requireSameClaimedEvidence(digest)
            ?: ContinuityClaimCheckpoint(now.epochSecond, digest, BackupIdentityTrustedComposition.newWitness())
        journal.markClaimed(checkpoint)
        trace.log("claim", "verified")
        return checkpoint to BackupIdentityTrustedComposition.authorize(request, verified.matched, aliases, checkpoint.witness)
    }

    private fun finish(
        space: FileDataSpace, verified: VerifiedContinuityRestore, checkpoint: ContinuityClaimCheckpoint, trace: ContinuityRestoreTrace,
    ): BackupRestoreUiStatus {
        val request = verified.attempt
        // Rebuild the expected seal only; no SQLite write is authorized during this readback.
        val expected = BackupIdentityTrustedComposition.authorize(request, verified.matched, verified.aliases, checkpoint.witness).expected
        space.withMaintenance {
            check(prepare(request.operationId).status == StagingStatus.ACTIVE)
            managed.finish(active, expected, verified)
        }
        journal.markCompleted(request.operationId)
        trace.log("publish_writer", "succeeded")
        return BackupRestoreUiStatus.APPLIED_CONTINUITY
    }

    private fun validate(source: File, files: ContinuityFiles, cancel: () -> Unit): ValidatedContinuityInput {
        val target = journal.target()
        if (journal.isCompleted(target.operationId)) failContinuity(ContinuityRestoreError.ALREADY_USED)
        val stored = StoredContinuityEvidence(files, target, managed.paired())
        val archive = inspectContinuityArchive(source, cancel)
        val identity = verifyContinuityRestore(source, archive, stored, managed.now(), cancel)
        return ValidatedContinuityInput(archive, identity, stored)
    }

    private fun verified(
        file: File, archive: ContinuityArchive, stored: StoredContinuityEvidence, checkpoint: ContinuityClaimCheckpoint?,
    ): VerifiedContinuityRestore {
        val now = if (checkpoint == null) managed.now() else {
            check(checkpoint.grantSha256 == evidenceDigest(stored.files))
            Instant.ofEpochSecond(checkpoint.epochSecond)
        }
        return verifyContinuityRestore(file, archive, stored, now) { }
    }
}

private val terminalClaimRejections = setOf(
    ContinuityClaimFailure.ALREADY_CLAIMED, ContinuityClaimFailure.ATTEMPT_NOT_PENDING, ContinuityClaimFailure.INVALID_REQUEST,
    ContinuityClaimFailure.CONDITIONAL_CREATE_UNVERIFIED, ContinuityClaimFailure.WRITER_NOT_RETIRED,
    ContinuityClaimFailure.WRITER_SEQUENCE_CHANGED, ContinuityClaimFailure.WRITER_SEQUENCE_CONFLICT,
)

private class ValidatedContinuityInput(
    val archive: ContinuityArchive, val identity: VerifiedContinuityRestore, val stored: StoredContinuityEvidence,
)

private class ColdContinuityInput(
    val archive: ContinuityArchive, val identity: VerifiedContinuityRestore, val checkpoint: ContinuityClaimCheckpoint?,
)

internal class ContinuityReview(
    val plan: com.deepstudent.services.backup.api.RestorePlan,
    val attempt: com.deepstudent.services.backup.data.BackupIdentityAttempt,
    val evidenceSha256: String,
    val lastSequence: Long,
) { override fun toString() = "ContinuityReview(redacted)" }

private fun evidenceDigest(files: ContinuityFiles) = Digests.sha256HexUtf8(
    listOf(files.grant, files.signature, files.keyDer, files.sidecar).joinToString(":") { Digests.sha256Hex(it) } + ":${files.fingerprint}",
)

private fun continuityCode(error: Exception): String = when (error) {
    is ContinuityRestoreException -> error.error.name
    is com.deepstudent.services.backup.backend.ContinuityGrantException -> error.reason.name
    is com.deepstudent.services.sync.backend.ContinuityClaimException -> error.reason.name
    else -> "CONTINUITY_IO"
}

private class ContinuityRestoreTrace(private val id: String) {
    private val start = SystemClock.elapsedRealtime()
    fun log(phase: String, state: String, code: String = "none") {
        Log.i("DstuRestoreIdentity", "event=backup.continuity owner=app.backup correlationId=$id phase=$phase " +
            "state=$state elapsedMs=${SystemClock.elapsedRealtime() - start} code=$code")
    }
}

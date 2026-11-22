package com.deepstudent.services.backup.data

import com.deepstudent.platform.storage.ActivationResult
import com.deepstudent.platform.storage.DataSpaceError
import com.deepstudent.platform.storage.DataSpaceException
import com.deepstudent.platform.storage.DataSpaceMaintenance
import com.deepstudent.platform.storage.DataSpaceStaging
import com.deepstudent.platform.storage.FileDataSpace
import com.deepstudent.platform.storage.StagingStatus
import com.deepstudent.services.backup.api.RestoreArchive
import com.deepstudent.services.backup.api.RestoreAdmission
import com.deepstudent.services.backup.api.RestorePlan
import com.deepstudent.services.backup.backend.ArchivePreflight
import java.io.File

/** Orchestration only; queue ownership and startup barrier remain in the main process. */
class RestoreExecutor private constructor(
    private val verifier: RestoreCandidateVerifier,
    private val audit: RestoreAudit = RestoreAudit.NONE,
    private val hook: RestoreCheckpointHook = RestoreCheckpointHook.NONE,
    private val cancelled: () -> Boolean,
    private val planReader: ArchivePlanReader,
) {
    constructor(
        verifier: RestoreCandidateVerifier,
        audit: RestoreAudit = RestoreAudit.NONE,
        hook: RestoreCheckpointHook = RestoreCheckpointHook.NONE,
        cancelled: () -> Boolean = { false },
    ) : this(verifier, audit, hook, cancelled, planReaderFor(RestoreAdmission.STANDARD))

    /** Rechecks the same routing policy used by the confirmed, app-private queue. */
    constructor(
        verifier: RestoreCandidateVerifier,
        admission: RestoreAdmission,
        audit: RestoreAudit = RestoreAudit.NONE,
        hook: RestoreCheckpointHook = RestoreCheckpointHook.NONE,
        cancelled: () -> Boolean = { false },
    ) : this(verifier, audit, hook, cancelled, planReaderFor(admission))

    internal constructor(
        verifier: RestoreCandidateVerifier,
        planReader: ArchivePlanReader,
        audit: RestoreAudit = RestoreAudit.NONE,
        hook: RestoreCheckpointHook = RestoreCheckpointHook.NONE,
        cancelled: () -> Boolean = { false },
    ) : this(verifier, audit, hook, cancelled, planReader)

    fun execute(
        space: FileDataSpace,
        operationId: String,
        confirmedPlan: RestorePlan,
        openArchive: () -> RestoreArchive,
    ): RestoreExecutionResult {
        if (!operationId.matches(Regex("[A-Za-z0-9._:-]{1,128}"))) {
            return RestoreExecutionResult.Failed(operationId = "invalid", phase = RestorePhase.ACQUIRE, error = RestoreError.INVALID_PLAN)
        }
        val trace = RestoreTrace(operationId, audit, hook, cancelled)
        return try {
            trace.enter(RestorePhase.ACQUIRE)
            space.withMaintenance {
                trace.succeeded()
                runInMaintenance(this, operationId, confirmedPlan, openArchive, trace)
            }
        } catch (error: DataSpaceException) {
            if (error.error == DataSpaceError.BUSY) RestoreExecutionResult.Busy
            else RestoreExecutionResult.NeedsRepair(operationId, trace.currentPhase, RestoreError.STORAGE_STATE)
        } catch (error: RestoreFailure) {
            trace.failed(error.code)
            RestoreExecutionResult.Failed(operationId, trace.currentPhase, error.code)
        } catch (_: Exception) {
            trace.failed(RestoreError.IO_FAILURE)
            RestoreExecutionResult.Failed(operationId, trace.currentPhase, RestoreError.IO_FAILURE)
        }
    }

    private fun runInMaintenance(
        scope: DataSpaceMaintenance,
        operationId: String,
        plan: RestorePlan,
        opener: () -> RestoreArchive,
        trace: RestoreTrace,
    ): RestoreExecutionResult {
        var stage: DataSpaceStaging? = null
        var validationError: RestoreError? = null
        var activationReturned = false
        try {
            trace.checkpoint(RestoreCheckpoint.MAINTENANCE_ACQUIRED)
            trace.enter(RestorePhase.PREPARE)
            checkPlan(plan)
            stage = scope.prepare(operationId)
            completed(operationId, stage.status)?.let {
                trace.succeeded()
                trace.enter(RestorePhase.COMPLETE)
                trace.succeeded()
                return it
            }
            scope.discard(stage)
            stage = scope.prepare(operationId)
            trace.checkpoint(RestoreCheckpoint.STAGING_RESET)
            trace.succeeded()
            val candidate = stage.root
            opener().use { archive ->
                recheck(plan, archive, candidate, trace)
                populate(scope.active, candidate, plan, archive, trace)
            }
            trace.enter(RestorePhase.ACTIVATE)
            trace.checkpoint(RestoreCheckpoint.BEFORE_ACTIVATION)
            val result = scope.activate(stage) { root ->
                try { validateCandidate(root, trace) }
                catch (error: RestoreFailure) { validationError = error.code; throw error }
            }
            activationReturned = true
            trace.checkpoint(RestoreCheckpoint.ACTIVATION_RETURNED)
            trace.succeeded()
            trace.enter(RestorePhase.COMPLETE)
            trace.succeeded()
            return committed(operationId, result)
        } catch (error: Exception) {
            return reconcile(scope, stage, operationId, trace, validationError ?: errorCode(error), activationReturned)
        }
    }

    private fun recheck(plan: RestorePlan, archive: RestoreArchive, candidate: File, trace: RestoreTrace) {
        trace.enter(RestorePhase.PREFLIGHT)
        checkCatalog(archive, plan)
        StagedFileWriter.manifest(archive, checkNotNull(plan.manifestSha256), trace)
        val actual = planReader.inspect(archive, candidate.usableSpace) {
            if (cancelled()) throw java.util.concurrent.CancellationException()
        }
        checkPlan(actual)
        if (!sameConfirmedPlan(plan, actual)) throw RestoreFailure(RestoreError.MANIFEST_CHANGED)
        trace.checkpoint(RestoreCheckpoint.ARCHIVE_RECHECKED)
        trace.succeeded()
    }

    private fun populate(active: File, candidate: File, plan: RestorePlan, archive: RestoreArchive, trace: RestoreTrace) {
        trace.enter(RestorePhase.SNAPSHOT)
        val snapshot = SnapshotCopy.enumerate(active, trace)
        val incoming = plan.files.fold(0L) { sum, file -> checkedSum(sum, file.size, MAX_RESTORE_BYTES) }
        SnapshotCopy.checkSpace(snapshot, candidate, incoming)
        SnapshotCopy.copy(snapshot, candidate, trace)
        trace.checkpoint(RestoreCheckpoint.SNAPSHOT_COMPLETE)
        trace.succeeded()
        trace.enter(RestorePhase.EXTRACT)
        for (file in plan.files) {
            StagedFileWriter.write(candidate.toPath(), archive, file, trace)
            trace.checkpoint(RestoreCheckpoint.ENTRY_WRITTEN)
        }
        trace.succeeded()
    }

    private fun validateCandidate(candidate: File, trace: RestoreTrace) {
        trace.enter(RestorePhase.VERIFY_DATABASES)
        try { verifier.verifyDatabases(candidate) } catch (_: Exception) { throw RestoreFailure(RestoreError.DATABASE_INVALID) }
        trace.checkpoint(RestoreCheckpoint.DATABASES_VERIFIED)
        trace.succeeded()
        trace.enter(RestorePhase.VERIFY_RESOURCES)
        try { verifier.verifyResources(candidate) } catch (_: Exception) { throw RestoreFailure(RestoreError.RESOURCE_INVALID) }
        trace.checkpoint(RestoreCheckpoint.RESOURCES_VERIFIED)
        trace.succeeded()
        trace.enter(RestorePhase.ACTIVATE)
    }
}

private fun sameConfirmedPlan(expected: RestorePlan, actual: RestorePlan): Boolean =
    expected.backupId == actual.backupId && expected.snapshotKind == actual.snapshotKind &&
        expected.legacyImport == actual.legacyImport && expected.source.admission == actual.source.admission &&
        expected.manifestSha256.equals(actual.manifestSha256, ignoreCase = true) &&
        expected.domains.sortedBy { it.id } == actual.domains.sortedBy { it.id } &&
        expected.files.sortedBy { it.archivePath } == actual.files.sortedBy { it.archivePath } &&
        expected.requiresPreservingUnmappedContent == actual.requiresPreservingUnmappedContent

private fun completed(operationId: String, status: StagingStatus): RestoreExecutionResult.Committed? = when (status) {
    StagingStatus.ACTIVE -> RestoreExecutionResult.Committed(operationId, CommitDisposition.ALREADY_ACTIVE)
    StagingStatus.PREVIOUSLY_ACTIVATED -> RestoreExecutionResult.Committed(operationId, CommitDisposition.PREVIOUSLY_COMMITTED)
    StagingStatus.WRITABLE -> null
}

private fun committed(operationId: String, result: ActivationResult): RestoreExecutionResult.Committed =
    RestoreExecutionResult.Committed(operationId, when (result) {
        ActivationResult.ACTIVATED -> CommitDisposition.NEWLY_COMMITTED
        ActivationResult.ALREADY_ACTIVE -> CommitDisposition.ALREADY_ACTIVE
        ActivationResult.PREVIOUSLY_ACTIVATED -> CommitDisposition.PREVIOUSLY_COMMITTED
    })

private fun reconcile(
    scope: DataSpaceMaintenance,
    stage: DataSpaceStaging?,
    operationId: String,
    trace: RestoreTrace,
    error: RestoreError,
    activationReturned: Boolean,
): RestoreExecutionResult {
    trace.failed(error)
    if (stage == null) {
        return if (error == RestoreError.STORAGE_STATE) RestoreExecutionResult.NeedsRepair(operationId, trace.currentPhase, error)
        else RestoreExecutionResult.Failed(operationId, trace.currentPhase, error)
    }
    return try {
        when (stage.status) {
            StagingStatus.ACTIVE -> if (activationReturned) {
                RestoreExecutionResult.Committed(operationId, CommitDisposition.ALREADY_ACTIVE)
            } else {
                RestoreExecutionResult.NeedsRepair(operationId, trace.currentPhase, RestoreError.STORAGE_STATE)
            }
            StagingStatus.PREVIOUSLY_ACTIVATED -> RestoreExecutionResult.Committed(operationId, CommitDisposition.PREVIOUSLY_COMMITTED)
            StagingStatus.WRITABLE -> {
                scope.discard(stage)
                RestoreExecutionResult.Failed(operationId, trace.currentPhase, error)
            }
        }
    } catch (_: Exception) {
        RestoreExecutionResult.NeedsRepair(operationId, trace.currentPhase, RestoreError.STORAGE_STATE)
    }
}

private fun errorCode(error: Exception): RestoreError = when (error) {
    is RestoreFailure -> error.code
    is java.util.concurrent.CancellationException -> RestoreError.CANCELLED
    is DataSpaceException -> RestoreError.STORAGE_STATE
    else -> RestoreError.IO_FAILURE
}

internal fun interface ArchivePlanReader { fun inspect(archive: RestoreArchive, availableBytes: Long, checkCancelled: () -> Unit): RestorePlan }

private fun planReaderFor(admission: RestoreAdmission) = ArchivePlanReader { archive, bytes, cancel ->
    when (admission) {
        RestoreAdmission.STANDARD -> ArchivePreflight.inspect(archive, bytes, cancel).plan
        RestoreAdmission.AUTHENTICATED_PACKAGE -> ArchivePreflight.inspectAuthenticated(archive, bytes, cancel).plan
    }
}

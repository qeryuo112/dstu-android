package com.deepstudent.app.backup

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import com.deepstudent.platform.backupzip.CheckedZipArchive
import com.deepstudent.platform.database.BundledMigrationSource
import com.deepstudent.platform.storage.FileDataSpace
import com.deepstudent.services.backup.api.RestoreAdmission
import com.deepstudent.services.backup.api.RestorePlan
import com.deepstudent.services.backup.backend.ArchivePreflight
import com.deepstudent.services.backup.data.RestoreAudit
import com.deepstudent.services.backup.data.RestoreExecutionResult
import com.deepstudent.services.backup.data.RestoreExecutor
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

internal enum class BackupRestoreUiStatus(val message: String?) {
    IDLE(null),
    QUEUING("正在复核并保存待恢复文件…"),
    QUEUED("恢复已排队。下次冷启动时执行；应用仍在后台运行时不会切换。"),
    APPLYING("正在校验暂存数据…"),
    APPLIED("恢复完成。上一数据槽已保留。"),
    APPLIED_REPAIR_SYNC("恢复完成。上一数据槽已保留；如需同步，请用新设备身份重新配对。"),
    APPLIED_CONTINUITY("连续迁移已完成。旧同步身份和序列已保留；请用新凭据重新配对到原仓库。"),
    CONTINUITY_FAILED("连续迁移校验失败，未切换数据槽。旧写者的授权状态请向仓库管理员确认。"),
    PACK_CHANGED("备份无法进入恢复。请重新选择和检查。"),
    SYNC_BOUND("此安装已登记同步身份。本阶段不对此安装执行普通备份切换。"),
    FAILED("恢复未通过校验。原活动槽未被替换。"),
    NEEDS_REVIEW("无法可靠确认恢复结果，已保留待核查资料。请勿清除应用数据。"),
}

/** Main-process owner. Activation happens on the first I/O access before a business lease can exist. */
internal class BackupRestoreCoordinator(
    private val context: Context,
    private val identity: OrdinaryRestoreIdentity? = null,
    private val continuity: ContinuityQueuedRestore? = null,
) {
    private val queue by lazy { PendingBackupRestore(context.noBackupFilesDir.resolve("backup-restore")) }
    private val lock = Any()
    private var startupChecked = false
    private val mutable = MutableStateFlow(BackupRestoreUiStatus.IDLE)
    val status = mutable.asStateFlow()

    /** A committed slot whose writer rotation has not completed must never write with the old ID. */
    fun requireStableSyncIdentity() {
        beforeBusinessOpen()
        check(status.value != BackupRestoreUiStatus.NEEDS_REVIEW) { "Restore identity needs restart or review" }
    }

    fun beforeBusinessOpen() = synchronized(lock) {
        if (startupChecked) return@synchronized
        try {
            val request = queue.request()
            if (request == null) {
                queue.removeUnqueuedInput()
                continuity?.cleanEvidence()
                mutable.value = queue.lastReceipt()?.let(BackupRestoreUiStatus::valueOf) ?: BackupRestoreUiStatus.IDLE
            } else {
                mutable.value = BackupRestoreUiStatus.APPLYING
                val result = recover(request)
                if (result != BackupRestoreUiStatus.NEEDS_REVIEW) {
                    queue.record(result.name)
                    if (request.continuity) continuity?.cleanEvidence()
                }
                mutable.value = result
            }
        } catch (_: Exception) {
            mutable.value = BackupRestoreUiStatus.NEEDS_REVIEW
        } finally {
            startupChecked = true
        }
    }

    fun issueContinuityTarget(renew: Boolean = false): ContinuityTarget = synchronized(lock) {
        check(queue.request() == null)
        checkNotNull(continuity).issueTarget(renew)
    }

    suspend fun reviewContinuity(source: Uri, files: ContinuityFiles): ContinuityReview = withContext(Dispatchers.IO) {
        val checkCancellation = { coroutineContext.ensureActive() }
        synchronized(lock) {
            check(queue.request() == null)
            checkNotNull(continuity).review(source, files, checkCancellation)
        }
    }

    suspend fun scheduleContinuity(source: Uri, files: ContinuityFiles, reviewed: ContinuityReview): Boolean =
        withContext(Dispatchers.IO) {
            val checkCancellation = { coroutineContext.ensureActive() }
            synchronized(lock) {
                if (mutable.value in BUSY || queue.request() != null) return@synchronized false
                val trace = RestoreQueueTrace(reviewed.attempt.operationId)
                mutable.value = BackupRestoreUiStatus.QUEUING
                trace.log("continuity_queue", "started")
                try {
                    mutable.value = checkNotNull(continuity).queue(queue, source, files, reviewed, checkCancellation)
                    trace.log("continuity_queue", mutable.value.name)
                    mutable.value == BackupRestoreUiStatus.QUEUED
                } catch (error: Exception) {
                    mutable.value = BackupRestoreUiStatus.IDLE
                    trace.log("continuity_queue", if (error is CancellationException) "cancelled" else "failed")
                    throw error
                }
            }
        }

    suspend fun schedule(source: Uri, confirmed: RestorePlan) = scheduleInput(confirmed, RestoreAdmission.STANDARD) { dest, cancel ->
        copyBackupSource(context, source, dest, cancel, durable = true)
    }

    /** S2.14 package authentication precedes scheduling; only an owned, rehashed ZIP may cross this seam. */
    suspend fun scheduleAuthenticated(source: ValidatedBackupZip, confirmed: RestorePlan, operationId: UUID) =
        scheduleInput(confirmed, RestoreAdmission.AUTHENTICATED_PACKAGE, operationId) { dest, cancel ->
            check(source.authenticated)
            copyAuthenticatedBackup(source, dest, cancel)
        }

    private suspend fun scheduleInput(
        confirmed: RestorePlan, admission: RestoreAdmission, operationId: UUID = UUID.randomUUID(),
        copy: (File, () -> Unit) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val checkCancellation = { coroutineContext.ensureActive() }
        synchronized(lock) {
            if (mutable.value in BUSY) return@synchronized false
            val requestId = operationId.toString()
            val trace = RestoreQueueTrace(requestId)
            mutable.value = BackupRestoreUiStatus.QUEUING
            trace.log("queue", "started")
            mutable.value = try {
                scheduleLocked(confirmed, admission, requestId, checkCancellation, copy)
            } catch (cancelled: CancellationException) {
                mutable.value = BackupRestoreUiStatus.IDLE
                trace.log("queue", "cancelled")
                throw cancelled
            } catch (_: Exception) {
                BackupRestoreUiStatus.FAILED
            }
            trace.log("queue", mutable.value.name)
            mutable.value == BackupRestoreUiStatus.QUEUED
        }
    }

    private fun scheduleLocked(
        confirmed: RestorePlan, admission: RestoreAdmission, id: String, cancel: () -> Unit, copy: (File, () -> Unit) -> Unit,
    ): BackupRestoreUiStatus {
        if (!permitsOrdinaryRestore()) return BackupRestoreUiStatus.SYNC_BOUND
        if (!confirmed.canStage || confirmed.manifestSha256 == null || confirmed.source.admission != admission) {
            return BackupRestoreUiStatus.PACK_CHANGED
        }
        queue.prepare()
        try {
            copy(queue.scratch, cancel)
            val validated = readPlan(queue.scratch, admission, cancel)
            if (!sameManifest(validated, confirmed.manifestSha256)) return BackupRestoreUiStatus.PACK_CHANGED
            cancel()
            queue.publish(BackupRestoreRequest(id, checkNotNull(validated.manifestSha256), admission))
            return BackupRestoreUiStatus.QUEUED
        } finally {
            Files.deleteIfExists(queue.scratch.toPath())
        }
    }

    private fun recover(request: BackupRestoreRequest): BackupRestoreUiStatus {
        val trace = RestoreQueueTrace(request.id)
        trace.log("startup", "started")
        val status = recoverInput(request)
        trace.log("startup", status.name)
        return status
    }

    private fun recoverInput(request: BackupRestoreRequest): BackupRestoreUiStatus {
        val space = FileDataSpace(context.filesDir)
        if (request.continuity) return continuity?.recover(space, queue, request) ?: BackupRestoreUiStatus.NEEDS_REVIEW
        val alreadyApplied = runCatching {
            space.withMaintenance {
                val stage = prepare(request.id)
                when (stage.status) {
                    com.deepstudent.platform.storage.StagingStatus.WRITABLE -> false
                    else -> true
                }
            }
        }.getOrElse { return BackupRestoreUiStatus.NEEDS_REVIEW }
        if (alreadyApplied) return finishIdentity(request.id)
        if (!permitsOrdinaryRestore()) return BackupRestoreUiStatus.SYNC_BOUND
        val plan = readPlan(queue.input, request.admission)
        if (!sameManifest(plan, request.manifestHash)) return BackupRestoreUiStatus.PACK_CHANGED
        return executeRestore(space, request.id, plan)
    }

    private fun executeRestore(space: FileDataSpace, operationId: String, plan: RestorePlan): BackupRestoreUiStatus {
        val legacy = plan.legacyImport?.let { LegacyRestoreChecks(plan, operationId) }
        val verifier = ordinaryRestoreVerifier(BundledMigrationSource(context), plan, legacy,
            androidIdentityLog(operationId), androidBaselineLog(operationId))
        val executor = RestoreExecutor(verifier, plan.source.admission, audit = AndroidRestoreAudit)
        return when (executor.execute(space, operationId, plan) { CheckedZipArchive(queue.input) }) {
            is RestoreExecutionResult.Committed -> finishIdentity(operationId)
            is RestoreExecutionResult.Failed -> BackupRestoreUiStatus.FAILED
            is RestoreExecutionResult.NeedsRepair, RestoreExecutionResult.Busy -> BackupRestoreUiStatus.NEEDS_REVIEW
        }
    }

    private fun finishIdentity(operationId: String) = if (identity?.finish(operationId) == true) {
        BackupRestoreUiStatus.APPLIED_REPAIR_SYNC
    } else BackupRestoreUiStatus.APPLIED

    private fun readPlan(file: File, admission: RestoreAdmission, cancel: () -> Unit = {}): RestorePlan = CheckedZipArchive(file).use {
        when (admission) {
            RestoreAdmission.STANDARD -> ArchivePreflight.inspect(it, file.usableSpace, cancel).plan
            RestoreAdmission.AUTHENTICATED_PACKAGE -> ArchivePreflight.inspectAuthenticated(it, file.usableSpace, cancel).plan
        }
    }

    private fun sameManifest(plan: RestorePlan, expected: String?): Boolean =
        plan.canStage && plan.manifestSha256 != null && plan.manifestSha256 == expected

    /** Without the identity publisher (older/isolated composition), the original guard still applies. */
    private fun permitsOrdinaryRestore(): Boolean {
        if (identity != null) return true
        val sync = context.noBackupFilesDir.resolve("sync")
        return Files.notExists(sync.resolve("configuration.json").toPath(), NOFOLLOW_LINKS) &&
            Files.notExists(sync.resolve("device-id").toPath(), NOFOLLOW_LINKS)
    }

    private companion object {
        val BUSY = setOf(BackupRestoreUiStatus.QUEUING, BackupRestoreUiStatus.QUEUED, BackupRestoreUiStatus.APPLYING, BackupRestoreUiStatus.NEEDS_REVIEW)
    }
}

internal val AndroidRestoreAudit = RestoreAudit { event ->
    Log.i("DstuBackupRestore", "event=backup.restore owner=${event.owner} correlationId=${event.operationId} " +
        "phase=${event.phase} state=${event.status} elapsedMs=${event.elapsedMillis} code=${event.error ?: "none"}")
}

private class RestoreQueueTrace(private val id: String) {
    private val start = SystemClock.elapsedRealtime()
    fun log(phase: String, state: String) {
        Log.i("DstuBackupRestore", "event=backup.queue owner=app.backup correlationId=$id " +
            "phase=$phase state=$state elapsedMs=${SystemClock.elapsedRealtime() - start}")
    }
}

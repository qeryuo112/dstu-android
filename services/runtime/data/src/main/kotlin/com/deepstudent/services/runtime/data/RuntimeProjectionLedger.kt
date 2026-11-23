package com.deepstudent.services.runtime.data

import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.core.common.logging.LogLevel
import com.deepstudent.core.common.logging.LogRecord
import com.deepstudent.core.common.logging.Logger
import com.deepstudent.core.common.time.ClockChecks
import com.deepstudent.platform.database.SqlDatabase
import com.deepstudent.platform.database.SqlOperations
import com.deepstudent.services.runtime.api.RuntimeApprovalRecord
import com.deepstudent.services.runtime.api.RuntimeApprovalState
import com.deepstudent.services.runtime.api.RuntimeAuditAction
import com.deepstudent.services.runtime.api.RuntimeAuditRecord
import com.deepstudent.services.runtime.api.RuntimeDutyRecord
import com.deepstudent.services.runtime.api.RuntimeProjectionChange
import com.deepstudent.services.runtime.api.RuntimeProjectionResult
import com.deepstudent.services.runtime.api.RuntimeRecordKey
import com.deepstudent.services.runtime.api.RuntimeTaskGeneration
import com.deepstudent.services.runtime.api.TaskCursor
import java.time.Instant

/**
 * Approval/audit/duty projections of one runtime task generation.
 *
 * These writes never grant capabilities and never start foreground services. Each change commits
 * the projection row, its audit row, one PROJECTION_CHANGED event and the task cursor in the same
 * SQLite transaction, so a reopened process reads back exactly what was written.
 */
internal class RuntimeProjectionLedger(private val database: SqlDatabase, private val logger: Logger) {
    suspend fun apply(task: RuntimeTaskGeneration, change: RuntimeProjectionChange, now: Instant): RuntimeProjectionResult {
        val started = System.nanoTime()
        val result = database.transaction { applyChange(task, change, now) }
        log(task, change, result, ClockChecks.monotonicElapsedMillis(started, System.nanoTime()))
        return result
    }

    suspend fun approvals(task: RuntimeTaskGeneration, after: RuntimeRecordKey?, limit: Int): List<RuntimeApprovalRecord> {
        require(limit in 1..MAX_PAGE) { "invalid approval page size" }
        return database.query(
            "SELECT record_id, operation, state, requested_at, updated_at, cursor FROM runtime_approvals " +
                "WHERE task_id = ? AND generation = ? AND record_id > ? ORDER BY record_id LIMIT ?",
            listOf(text(task.taskId.value), integer(task.generation), text(after?.value ?: ""), integer(limit.toLong())),
            RuntimeProjectionRows::approval,
        )
    }

    suspend fun audit(task: RuntimeTaskGeneration, after: TaskCursor, limit: Int): List<RuntimeAuditRecord> {
        require(limit in 1..MAX_PAGE) { "invalid audit page size" }
        return database.query(
            "SELECT record_id, action, code, at, cursor FROM runtime_audit " +
                "WHERE task_id = ? AND generation = ? AND cursor > ? ORDER BY cursor LIMIT ?",
            listOf(text(task.taskId.value), integer(task.generation), integer(after.value), integer(limit.toLong())),
            RuntimeProjectionRows::audit,
        )
    }

    suspend fun duties(task: RuntimeTaskGeneration, after: RuntimeRecordKey?, limit: Int): List<RuntimeDutyRecord> {
        require(limit in 1..MAX_PAGE) { "invalid duty page size" }
        return database.query(
            "SELECT record_id, purpose, registered_at, released_at, cursor FROM runtime_duties " +
                "WHERE task_id = ? AND generation = ? AND record_id > ? ORDER BY record_id LIMIT ?",
            listOf(text(task.taskId.value), integer(task.generation), text(after?.value ?: ""), integer(limit.toLong())),
            RuntimeProjectionRows::duty,
        )
    }

    private fun SqlOperations.applyChange(
        task: RuntimeTaskGeneration,
        change: RuntimeProjectionChange,
        now: Instant,
    ): RuntimeProjectionResult {
        val snapshot = query(
            "SELECT ${RuntimeRows.COLUMNS} FROM runtime_tasks WHERE task_id = ?",
            listOf(text(task.taskId.value)),
            RuntimeRows::snapshot,
        ).singleOrNull() ?: return RuntimeProjectionResult.MISSING
        if (snapshot.ownership.generation != task.generation) return RuntimeProjectionResult.STALE_GENERATION
        decide(task, change)?.let { return it }
        if (snapshot.state.terminal && change.requiresOpenTask()) return RuntimeProjectionResult.CLOSED
        persistChange(task, change, now, appendProjectionEvent(snapshot, now))
        return RuntimeProjectionResult.APPLIED
    }

    /** Writes the audit row, the projection row and the cursor of one accepted change. */
    private fun SqlOperations.persistChange(
        task: RuntimeTaskGeneration,
        change: RuntimeProjectionChange,
        now: Instant,
        cursor: TaskCursor,
    ) {
        when (change) {
            is RuntimeProjectionChange.RequestApproval -> {
                insertAudit(task, cursor, change.id, RuntimeAuditAction.APPROVAL_REQUESTED, change.operation, now)
                insertApproval(task, cursor, change, now)
            }

            is RuntimeProjectionChange.ResolveApproval -> {
                insertAudit(task, cursor, change.id, RuntimeAuditAction.APPROVAL_RESOLVED, approvalCode(change.state), now)
                resolveApproval(task, cursor, change, now)
            }

            is RuntimeProjectionChange.RecordDecision ->
                insertAudit(task, cursor, change.id, RuntimeAuditAction.DECISION_RECORDED, change.code, now)

            is RuntimeProjectionChange.RegisterDuty -> {
                insertAudit(task, cursor, change.id, RuntimeAuditAction.DUTY_REGISTERED, change.purpose, now)
                insertDuty(task, cursor, change, now)
            }

            is RuntimeProjectionChange.ReleaseDuty -> {
                val purpose = requireNotNull(dutyPurpose(task, change.id))
                insertAudit(task, cursor, change.id, RuntimeAuditAction.DUTY_RELEASED, purpose, now)
                releaseDuty(task, cursor, change.id, now)
            }
        }
    }

    /** `null` means the change may be written; anything else is returned without touching a row. */
    private fun SqlOperations.decide(task: RuntimeTaskGeneration, change: RuntimeProjectionChange): RuntimeProjectionResult? =
        when (change) {
            is RuntimeProjectionChange.RequestApproval -> decideApprovalRequest(task, change)
            is RuntimeProjectionChange.ResolveApproval -> decideApprovalResolution(task, change)
            is RuntimeProjectionChange.RecordDecision -> decideDecision(task, change)
            is RuntimeProjectionChange.RegisterDuty -> decideDutyRegistration(task, change)
            is RuntimeProjectionChange.ReleaseDuty -> decideDutyRelease(task, change.id)
        }

    private fun SqlOperations.decideApprovalRequest(
        task: RuntimeTaskGeneration,
        change: RuntimeProjectionChange.RequestApproval,
    ): RuntimeProjectionResult? {
        val row = approvalRow(task, change.id) ?: return null
        return if (row.operation == change.operation.value && row.state == RuntimeApprovalState.PENDING.name) {
            RuntimeProjectionResult.UNCHANGED
        } else {
            RuntimeProjectionResult.CONFLICT
        }
    }

    private fun SqlOperations.decideApprovalResolution(
        task: RuntimeTaskGeneration,
        change: RuntimeProjectionChange.ResolveApproval,
    ): RuntimeProjectionResult? {
        val row = approvalRow(task, change.id) ?: return RuntimeProjectionResult.MISSING
        return when (row.state) {
            change.state.name -> RuntimeProjectionResult.UNCHANGED
            RuntimeApprovalState.PENDING.name -> null
            else -> RuntimeProjectionResult.CONFLICT
        }
    }

    private fun SqlOperations.decideDecision(
        task: RuntimeTaskGeneration,
        change: RuntimeProjectionChange.RecordDecision,
    ): RuntimeProjectionResult? {
        val existing = auditCode(task, change.id, RuntimeAuditAction.DECISION_RECORDED) ?: return null
        return if (existing == change.code.value) {
            RuntimeProjectionResult.UNCHANGED
        } else {
            RuntimeProjectionResult.CONFLICT
        }
    }

    private fun SqlOperations.decideDutyRegistration(
        task: RuntimeTaskGeneration,
        change: RuntimeProjectionChange.RegisterDuty,
    ): RuntimeProjectionResult? {
        val row = dutyRow(task, change.id) ?: return null
        return if (row.purpose == change.purpose.value && !row.released) {
            RuntimeProjectionResult.UNCHANGED
        } else {
            RuntimeProjectionResult.CONFLICT
        }
    }

    private fun SqlOperations.decideDutyRelease(task: RuntimeTaskGeneration, id: RuntimeRecordKey): RuntimeProjectionResult? {
        val row = dutyRow(task, id) ?: return RuntimeProjectionResult.MISSING
        return if (row.released) RuntimeProjectionResult.UNCHANGED else null
    }

    private fun log(task: RuntimeTaskGeneration, change: RuntimeProjectionChange, result: RuntimeProjectionResult, elapsedMillis: Long?) {
        if (result == RuntimeProjectionResult.UNCHANGED) return
        val applied = result == RuntimeProjectionResult.APPLIED
        val builder = JsonValue.Object.Builder()
            .put("phase", "projection")
            .put("owner", OWNER)
            .put("generation", task.generation)
            .put("change", change.javaClass.simpleName)
            .put("result", result.name)
        if (elapsedMillis != null) builder.put("duration_ms", elapsedMillis)
        val record = LogRecord(
            if (applied) LogLevel.DEBUG else LogLevel.WARN,
            if (applied) "runtime.projection.applied" else "runtime.projection.rejected",
            taskId = task.taskId,
            fields = builder.build(),
        )
        // A failing sink must never turn an already committed transaction into a caller-visible failure.
        runCatching { logger.log(record) }
    }

    private fun approvalCode(state: RuntimeApprovalState): RuntimeRecordKey = RuntimeRecordKey.parse(state.name)

    private companion object {
        const val MAX_PAGE = 1000
        const val OWNER = "services/runtime/data"
    }
}

/** True for approvals/duties: once the task is terminal only their cleanup path is accepted. */
private fun RuntimeProjectionChange.requiresOpenTask(): Boolean = when (this) {
    is RuntimeProjectionChange.RequestApproval, is RuntimeProjectionChange.RegisterDuty -> true
    is RuntimeProjectionChange.ResolveApproval -> state != RuntimeApprovalState.CANCELLED && state != RuntimeApprovalState.DENIED
    is RuntimeProjectionChange.RecordDecision, is RuntimeProjectionChange.ReleaseDuty -> false
}

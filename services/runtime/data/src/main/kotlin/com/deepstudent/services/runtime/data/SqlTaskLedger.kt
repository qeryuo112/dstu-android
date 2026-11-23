package com.deepstudent.services.runtime.data

import com.deepstudent.core.common.id.TaskId
import com.deepstudent.core.common.logging.Logger
import com.deepstudent.core.common.logging.NoopLogger
import com.deepstudent.platform.database.SqlDatabase
import com.deepstudent.platform.database.SqlOperations
import com.deepstudent.services.runtime.api.RuntimeApprovalRecord
import com.deepstudent.services.runtime.api.RuntimeAuditRecord
import com.deepstudent.services.runtime.api.RuntimeDutyRecord
import com.deepstudent.services.runtime.api.RuntimeEvent
import com.deepstudent.services.runtime.api.RuntimeEventType
import com.deepstudent.services.runtime.api.RuntimeExecution
import com.deepstudent.services.runtime.api.RuntimeOwnership
import com.deepstudent.services.runtime.api.RuntimeProjectionChange
import com.deepstudent.services.runtime.api.RuntimeProjectionResult
import com.deepstudent.services.runtime.api.RuntimeProjectionStore
import com.deepstudent.services.runtime.api.RuntimeRecordKey
import com.deepstudent.services.runtime.api.RuntimeSnapshot
import com.deepstudent.services.runtime.api.RuntimeState
import com.deepstudent.services.runtime.api.RuntimeTaskGeneration
import com.deepstudent.services.runtime.api.TaskCursor
import com.deepstudent.services.runtime.api.TaskSubmission
import com.deepstudent.services.runtime.api.TaskTimes
import com.deepstudent.services.runtime.backend.LedgerCommand
import com.deepstudent.services.runtime.backend.LedgerCreate
import com.deepstudent.services.runtime.backend.LedgerResult
import com.deepstudent.services.runtime.backend.RuntimeStateMachine
import com.deepstudent.services.runtime.backend.TaskLedger
import java.time.Instant

/**
 * The caller owns and closes the local database returned by DatabaseFactory.openLocal.
 *
 * Approvals, audit rows and duties live here as local projections; [RuntimeProjectionLedger] keeps
 * their SQL. None of these calls grant capabilities or start Android foreground services.
 */
class SqlTaskLedger(private val database: SqlDatabase, logger: Logger = NoopLogger) :
    TaskLedger,
    RuntimeProjectionStore {
    private val projections = RuntimeProjectionLedger(database, logger)

    init {
        RuntimeSchema.initialize(database)
    }

    override suspend fun create(taskId: TaskId, submission: TaskSubmission, now: Instant): LedgerCreate = database.transaction {
        val existing = submission.dedupeKey?.let { findDedupe(submission) }
        if (existing != null) {
            check(existing.submission.payload == submission.payload) { "task dedupe conflict" }
            return@transaction LedgerCreate(existing, false)
        }
        val snapshot = RuntimeSnapshot(
            taskId,
            submission.kind,
            RuntimeState.CREATED,
            submission,
            RuntimeOwnership(1, 0, null, null),
            RuntimeExecution(null, false, null, null, null),
            TaskTimes(now, now),
            TaskCursor.ZERO,
        )
        execute(INSERT_TASK, RuntimeRows.values(snapshot))
        val stored = append(snapshot, RuntimeEventType.CREATED, now)
        LedgerCreate(stored, true)
    }

    override suspend fun snapshot(taskId: TaskId): RuntimeSnapshot? = database.find(taskId)

    override suspend fun events(taskId: TaskId, after: TaskCursor): List<RuntimeEvent> = database.query(
        "SELECT cursor, task_id, generation, type, state, at FROM runtime_events " +
            "WHERE task_id = ? AND cursor > ? ORDER BY cursor",
        listOf(text(taskId.value), integer(after.value)),
        RuntimeRows::event,
    )

    override suspend fun recoverable(afterTaskId: String?, limit: Int): List<RuntimeSnapshot> {
        require(limit in 1..1000) { "invalid recovery page size" }
        return database.query(
            "SELECT ${RuntimeRows.COLUMNS} FROM runtime_tasks WHERE state NOT IN " +
                "('COMPLETED','FAILED','CANCELLED','INTERRUPTED') AND task_id > ? ORDER BY task_id LIMIT ?",
            listOf(text(afterTaskId ?: ""), integer(limit.toLong())),
            RuntimeRows::snapshot,
        )
    }

    override suspend fun apply(taskId: TaskId, command: LedgerCommand, now: Instant): LedgerResult = database.transaction {
        val snapshot = find(taskId) ?: return@transaction LedgerResult.Missing
        val change = RuntimeStateMachine.decide(snapshot, command, now)
        val applied = change.result as? LedgerResult.Applied ?: return@transaction change.result
        val event = requireNotNull(change.event)
        LedgerResult.Applied(append(applied.snapshot, event, now))
    }

    override suspend fun apply(task: RuntimeTaskGeneration, change: RuntimeProjectionChange, now: Instant): RuntimeProjectionResult =
        projections.apply(task, change, now)

    override suspend fun approvals(task: RuntimeTaskGeneration, after: RuntimeRecordKey?, limit: Int): List<RuntimeApprovalRecord> =
        projections.approvals(task, after, limit)

    override suspend fun audit(task: RuntimeTaskGeneration, after: TaskCursor, limit: Int): List<RuntimeAuditRecord> =
        projections.audit(task, after, limit)

    override suspend fun duties(task: RuntimeTaskGeneration, after: RuntimeRecordKey?, limit: Int): List<RuntimeDutyRecord> =
        projections.duties(task, after, limit)

    /** Call only after acquiring exclusive process ownership, before accepting new foreground references. */
    suspend fun closePreviousForegroundDuties(now: Instant) = projections.closeForegroundProjectionRows(now)

    private fun SqlOperations.findDedupe(submission: TaskSubmission): RuntimeSnapshot? = query(
        "SELECT ${RuntimeRows.COLUMNS} FROM runtime_tasks WHERE kind = ? AND dedupe_key = ?",
        listOf(text(submission.kind.value), text(requireNotNull(submission.dedupeKey))),
        RuntimeRows::snapshot,
    ).singleOrNull()

    private fun SqlOperations.append(snapshot: RuntimeSnapshot, type: RuntimeEventType, now: Instant): RuntimeSnapshot {
        execute(
            "INSERT INTO runtime_events(task_id, generation, type, state, at) VALUES (?, ?, ?, ?, ?)",
            listOf(
                text(snapshot.taskId.value),
                integer(snapshot.ownership.generation),
                text(type.name),
                text(snapshot.state.name),
                text(now.toString()),
            ),
        )
        val cursor = TaskCursor.parse(query("SELECT last_insert_rowid()") { it.long(0) }.single())
        val updated = snapshot.copy(cursor = cursor)
        execute(UPDATE_TASK, RuntimeRows.values(updated).drop(1) + text(updated.taskId.value))
        return updated
    }

    private companion object {
        val INSERT_TASK = "INSERT INTO runtime_tasks (${RuntimeRows.COLUMNS}) VALUES " +
            "(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
        const val UPDATE_TASK = "UPDATE runtime_tasks SET kind=?, payload=?, correlation_id=?, dedupe_key=?, " +
            "state=?, generation=?, owner_epoch=?, owner_id=?, lease_until=?, checkpoint=?, cancel_requested=?, " +
            "receipt=?, business_fact_id=?, error_code=?, created_at=?, updated_at=?, cursor=?, confirmation_required=? WHERE task_id=?"
    }
}

private fun SqlOperations.find(taskId: TaskId): RuntimeSnapshot? = query(
    "SELECT ${RuntimeRows.COLUMNS} FROM runtime_tasks WHERE task_id = ?",
    listOf(text(taskId.value)),
    RuntimeRows::snapshot,
).singleOrNull()

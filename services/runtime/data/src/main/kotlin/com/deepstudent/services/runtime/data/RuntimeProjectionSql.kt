package com.deepstudent.services.runtime.data

import com.deepstudent.platform.database.SqlOperations
import com.deepstudent.services.runtime.api.RuntimeApprovalState
import com.deepstudent.services.runtime.api.RuntimeAuditAction
import com.deepstudent.services.runtime.api.RuntimeEventType
import com.deepstudent.services.runtime.api.RuntimeProjectionChange
import com.deepstudent.services.runtime.api.RuntimeRecordKey
import com.deepstudent.services.runtime.api.RuntimeSnapshot
import com.deepstudent.services.runtime.api.RuntimeTaskGeneration
import com.deepstudent.services.runtime.api.TaskCursor
import java.time.Instant

/** SQL helpers shared by [RuntimeProjectionLedger]; all values are opaque machine codes. */
internal data class ApprovalRow(val operation: String, val state: String, val binding: String?)

internal data class DutyRow(val purpose: String, val released: Boolean)

internal fun SqlOperations.approvalRow(task: RuntimeTaskGeneration, id: RuntimeRecordKey): ApprovalRow? = query(
    "SELECT operation, state, capability_binding FROM runtime_approvals WHERE task_id = ? AND generation = ? AND record_id = ?",
    listOf(text(task.taskId.value), integer(task.generation), text(id.value)),
) { ApprovalRow(it.text(0), it.text(1), it.optional(2)) }.singleOrNull()

internal fun SqlOperations.dutyRow(task: RuntimeTaskGeneration, id: RuntimeRecordKey): DutyRow? = query(
    "SELECT purpose, released_at IS NOT NULL FROM runtime_duties WHERE task_id = ? AND generation = ? AND record_id = ?",
    listOf(text(task.taskId.value), integer(task.generation), text(id.value)),
) { DutyRow(it.text(0), it.long(1) != 0L) }.singleOrNull()

internal fun SqlOperations.dutyPurpose(task: RuntimeTaskGeneration, id: RuntimeRecordKey): RuntimeRecordKey? = query(
    "SELECT purpose FROM runtime_duties WHERE task_id = ? AND generation = ? AND record_id = ?",
    listOf(text(task.taskId.value), integer(task.generation), text(id.value)),
) { RuntimeRecordKey.parse(it.text(0)) }.singleOrNull()

internal fun SqlOperations.auditCode(task: RuntimeTaskGeneration, id: RuntimeRecordKey, action: RuntimeAuditAction): String? = query(
    "SELECT code FROM runtime_audit WHERE task_id = ? AND generation = ? AND record_id = ? AND action = ?",
    listOf(text(task.taskId.value), integer(task.generation), text(id.value), text(action.name)),
) { it.text(0) }.singleOrNull()

/**
 * Writes the PROJECTION_CHANGED event and advances only the task cursor.
 *
 * `runtime_tasks.updated_at` is deliberately left alone: the state machine uses it as the
 * clock-regression guard for leases, and a late audit must not move it backwards or make an owner
 * look expired. Projection time lives in the audit/approval/duty `at` columns.
 */
internal fun SqlOperations.appendProjectionEvent(snapshot: RuntimeSnapshot, now: Instant): TaskCursor {
    execute(
        "INSERT INTO runtime_events(task_id, generation, type, state, at) VALUES (?, ?, ?, ?, ?)",
        listOf(
            text(snapshot.taskId.value),
            integer(snapshot.ownership.generation),
            text(RuntimeEventType.PROJECTION_CHANGED.name),
            text(snapshot.state.name),
            text(now.toString()),
        ),
    )
    val cursor = TaskCursor.parse(query("SELECT last_insert_rowid()") { it.long(0) }.single())
    execute(
        "UPDATE runtime_tasks SET cursor = ? WHERE task_id = ?",
        listOf(integer(cursor.value), text(snapshot.taskId.value)),
    )
    return cursor
}

internal fun SqlOperations.insertAudit(
    task: RuntimeTaskGeneration,
    cursor: TaskCursor,
    id: RuntimeRecordKey,
    action: RuntimeAuditAction,
    code: RuntimeRecordKey,
    now: Instant,
) {
    execute(
        "INSERT INTO runtime_audit(cursor, task_id, generation, record_id, action, code, at) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?)",
        listOf(
            integer(cursor.value),
            text(task.taskId.value),
            integer(task.generation),
            text(id.value),
            text(action.name),
            text(code.value),
            text(now.toString()),
        ),
    )
}

internal fun SqlOperations.insertApproval(
    task: RuntimeTaskGeneration,
    cursor: TaskCursor,
    change: RuntimeProjectionChange.RequestApproval,
    now: Instant,
) {
    execute(
        "INSERT INTO runtime_approvals(task_id, generation, record_id, operation, state, " +
            "requested_at, updated_at, cursor, capability_binding) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
        listOf(
            text(task.taskId.value),
            integer(task.generation),
            text(change.id.value),
            text(change.operation.value),
            text(RuntimeApprovalState.PENDING.name),
            text(now.toString()),
            text(now.toString()),
            integer(cursor.value),
            nullable(change.binding?.let(CapabilityRows::encode)),
        ),
    )
}

internal fun SqlOperations.resolveApproval(
    task: RuntimeTaskGeneration,
    cursor: TaskCursor,
    change: RuntimeProjectionChange.ResolveApproval,
    now: Instant,
) {
    execute(
        "UPDATE runtime_approvals SET state = ?, updated_at = ?, cursor = ? " +
            "WHERE task_id = ? AND generation = ? AND record_id = ?",
        listOf(
            text(change.state.name),
            text(now.toString()),
            integer(cursor.value),
            text(task.taskId.value),
            integer(task.generation),
            text(change.id.value),
        ),
    )
}

internal fun SqlOperations.insertDuty(
    task: RuntimeTaskGeneration,
    cursor: TaskCursor,
    change: RuntimeProjectionChange.RegisterDuty,
    now: Instant,
) {
    execute(
        "INSERT INTO runtime_duties(task_id, generation, record_id, purpose, registered_at, " +
            "released_at, cursor) VALUES (?, ?, ?, ?, ?, NULL, ?)",
        listOf(
            text(task.taskId.value),
            integer(task.generation),
            text(change.id.value),
            text(change.purpose.value),
            text(now.toString()),
            integer(cursor.value),
        ),
    )
}

internal fun SqlOperations.releaseDuty(task: RuntimeTaskGeneration, cursor: TaskCursor, id: RuntimeRecordKey, now: Instant) {
    execute(
        "UPDATE runtime_duties SET released_at = ?, cursor = ? " +
            "WHERE task_id = ? AND generation = ? AND record_id = ?",
        listOf(
            text(now.toString()),
            integer(cursor.value),
            text(task.taskId.value),
            integer(task.generation),
            text(id.value),
        ),
    )
}

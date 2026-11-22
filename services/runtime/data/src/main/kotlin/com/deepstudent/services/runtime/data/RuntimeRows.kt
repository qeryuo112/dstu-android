package com.deepstudent.services.runtime.data

import com.deepstudent.core.common.error.DstuError
import com.deepstudent.core.common.id.CorrelationId
import com.deepstudent.core.common.id.TaskId
import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.platform.database.SqlRow
import com.deepstudent.platform.database.SqlValue
import com.deepstudent.services.runtime.api.RuntimeEvent
import com.deepstudent.services.runtime.api.RuntimeEventType
import com.deepstudent.services.runtime.api.RuntimeExecution
import com.deepstudent.services.runtime.api.RuntimeOwnership
import com.deepstudent.services.runtime.api.RuntimeSnapshot
import com.deepstudent.services.runtime.api.RuntimeState
import com.deepstudent.services.runtime.api.TaskCursor
import com.deepstudent.services.runtime.api.TaskKind
import com.deepstudent.services.runtime.api.TaskSubmission
import com.deepstudent.services.runtime.api.TaskTimes
import java.time.Instant

internal object RuntimeRows {
    const val COLUMNS = "task_id, kind, payload, correlation_id, dedupe_key, state, generation, owner_epoch, " +
        "owner_id, lease_until, checkpoint, cancel_requested, receipt, business_fact_id, error_code, " +
        "created_at, updated_at, cursor, confirmation_required"

    fun snapshot(row: SqlRow): RuntimeSnapshot {
        val taskId = TaskId.parse(row.text(0))
        val kind = TaskKind.parse(row.text(1))
        val submission = TaskSubmission(kind, JsonValue.parse(row.text(2)), row.optional(3)?.let(CorrelationId::parse), row.optional(4))
        val ownership = RuntimeOwnership(row.long(6), row.long(7), row.optional(8), row.optional(9)?.let(Instant::parse))
        val error = row.optional(14)?.let { DstuError.internal(it, "task execution failed") }
        val execution = RuntimeExecution(row.optional(10)?.let(JsonValue::parse), row.long(11) != 0L, row.optional(12), row.optional(13), error, row.long(18) != 0L)
        val times = TaskTimes(Instant.parse(row.text(15)), Instant.parse(row.text(16)))
        return RuntimeSnapshot(taskId, kind, RuntimeState.valueOf(row.text(5)), submission, ownership, execution, times, TaskCursor.parse(row.long(17)))
    }

    fun event(row: SqlRow): RuntimeEvent = RuntimeEvent(
        TaskCursor.parse(row.long(0)), TaskId.parse(row.text(1)), row.long(2),
        RuntimeEventType.valueOf(row.text(3)), RuntimeState.valueOf(row.text(4)), Instant.parse(row.text(5)),
    )

    fun values(s: RuntimeSnapshot): List<SqlValue> = listOf(
        text(s.taskId.value), text(s.kind.value), text(s.submission.payload.canonical()),
        nullable(s.correlationId?.value), nullable(s.submission.dedupeKey), text(s.state.name),
        integer(s.ownership.generation), integer(s.ownership.ownerEpoch), nullable(s.ownership.ownerId),
        nullable(s.ownership.leaseUntil?.toString()), nullable(s.execution.checkpoint?.canonical()),
        integer(if (s.execution.cancelRequested) 1 else 0), nullable(s.execution.receipt),
        nullable(s.execution.businessFactId), nullable(s.execution.error?.code), text(s.times.createdAt.toString()),
        text(s.times.updatedAt.toString()), integer(s.cursor.value), integer(if (s.execution.confirmationRequired) 1 else 0),
    )
}

internal fun SqlRow.optional(index: Int): String? = if (isNull(index)) null else text(index)
internal fun text(value: String): SqlValue = SqlValue.Text(value)
internal fun nullable(value: String?): SqlValue = value?.let(SqlValue::Text) ?: SqlValue.Null
internal fun integer(value: Long): SqlValue = SqlValue.Integer(value)

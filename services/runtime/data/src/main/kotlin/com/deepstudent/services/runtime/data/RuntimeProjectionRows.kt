package com.deepstudent.services.runtime.data

import com.deepstudent.platform.database.SqlRow
import com.deepstudent.services.runtime.api.RuntimeApprovalRecord
import com.deepstudent.services.runtime.api.RuntimeApprovalState
import com.deepstudent.services.runtime.api.RuntimeAuditAction
import com.deepstudent.services.runtime.api.RuntimeAuditRecord
import com.deepstudent.services.runtime.api.RuntimeDutyRecord
import com.deepstudent.services.runtime.api.RuntimeRecordKey
import com.deepstudent.services.runtime.api.TaskCursor
import java.time.Instant

/** Rows of the local approval/audit/duty projections; every value is an opaque machine code. */
internal object RuntimeProjectionRows {
    fun approval(row: SqlRow): RuntimeApprovalRecord = RuntimeApprovalRecord(
        RuntimeRecordKey.parse(row.text(0)),
        RuntimeRecordKey.parse(row.text(1)),
        RuntimeApprovalState.valueOf(row.text(2)),
        Instant.parse(row.text(3)),
        Instant.parse(row.text(4)),
        TaskCursor.parse(row.long(5)),
    )

    fun audit(row: SqlRow): RuntimeAuditRecord = RuntimeAuditRecord(
        RuntimeRecordKey.parse(row.text(0)),
        RuntimeAuditAction.valueOf(row.text(1)),
        RuntimeRecordKey.parse(row.text(2)),
        Instant.parse(row.text(3)),
        TaskCursor.parse(row.long(4)),
    )

    fun duty(row: SqlRow): RuntimeDutyRecord = RuntimeDutyRecord(
        RuntimeRecordKey.parse(row.text(0)),
        RuntimeRecordKey.parse(row.text(1)),
        Instant.parse(row.text(2)),
        row.optional(3)?.let(Instant::parse),
        TaskCursor.parse(row.long(4)),
    )
}

package com.deepstudent.services.runtime.data

import com.deepstudent.core.common.id.TaskId
import com.deepstudent.platform.database.SqlRow
import com.deepstudent.services.runtime.api.CapabilityAddress
import com.deepstudent.services.runtime.api.CapabilityBinding
import com.deepstudent.services.runtime.api.CapabilityBudget
import com.deepstudent.services.runtime.api.CapabilityTicket
import com.deepstudent.services.runtime.api.RuntimeRecordKey
import com.deepstudent.services.runtime.api.RuntimeTaskGeneration
import java.time.Instant

/** Local v4 representation; components exclude the separator. Never contains command text or credentials. */
internal object CapabilityRows {
    const val PENDING = "SELECT a.task_id, a.generation, a.record_id, a.operation, a.capability_binding, a.requested_at " +
        "FROM runtime_approvals a JOIN runtime_tasks t ON a.task_id = t.task_id AND a.generation = t.generation " +
        "WHERE a.state = 'PENDING' AND a.capability_binding IS NOT NULL"

    fun ticket(row: SqlRow): CapabilityTicket = CapabilityTicket(
        CapabilityAddress(
            RuntimeTaskGeneration(TaskId.parse(row.text(0)), row.long(1)),
            RuntimeRecordKey.parse(row.text(2)),
        ),
        RuntimeRecordKey.parse(row.text(3)),
        decode(row.text(4)),
        Instant.parse(row.text(5)),
    )

    fun encode(binding: CapabilityBinding): String = with(binding) {
        listOf(caller.value, target.value, uid, resource.value, fingerprint, budget.durationMillis, budget.outputBytes)
            .joinToString("|")
    }

    fun decode(value: String): CapabilityBinding {
        val fields = value.split('|')
        check(fields.size == 7) { "invalid persisted capability binding" }
        return CapabilityBinding(
            RuntimeRecordKey.parse(fields[0]),
            RuntimeRecordKey.parse(fields[1]),
            fields[2].toInt(),
            RuntimeRecordKey.parse(fields[3]),
            fields[4],
            CapabilityBudget(fields[5].toLong(), fields[6].toInt()),
        )
    }
}

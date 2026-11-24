package com.deepstudent.services.runtime.data

import com.deepstudent.core.common.id.TaskId
import com.deepstudent.platform.database.SqlDatabase
import com.deepstudent.platform.database.SqlReadOperations
import com.deepstudent.services.runtime.api.CapabilityEpoch
import com.deepstudent.services.runtime.api.CapabilityEpochStore
import java.time.Instant

/** Uses the migrated chat_v2 table (V20260910). This adapter does not own the database lease. */
class SqlCapabilityEpochStore(private val database: SqlDatabase) : CapabilityEpochStore {
    override fun current(task: TaskId): CapabilityEpoch = database.readSnapshot { readEpoch(task) }

    override fun revokeTask(task: TaskId, now: Instant): Long = database.transaction {
        val current = readEpoch(task)
        val next = Math.addExact(maxOf(current.global, current.task), 1)
        execute(
            "INSERT INTO revocation_epochs(kind, task_id, epoch, updated_at) VALUES ('task', ?, ?, ?) " +
                "ON CONFLICT(kind, task_id) DO UPDATE SET epoch = excluded.epoch, updated_at = excluded.updated_at",
            listOf(text(task.value), integer(next), text(now.toString())),
        )
        next
    }

    private fun SqlReadOperations.readEpoch(task: TaskId): CapabilityEpoch {
        val rows = query(
            "SELECT kind, epoch FROM revocation_epochs WHERE (kind = 'global' AND task_id = '') OR (kind = 'task' AND task_id = ?)",
            listOf(text(task.value)),
        ) { it.text(0) to it.long(1).also { epoch -> check(epoch >= 0L) { "invalid revocation epoch" } } }.toMap()
        return CapabilityEpoch(rows["global"] ?: 0, rows["task"] ?: 0)
    }
}

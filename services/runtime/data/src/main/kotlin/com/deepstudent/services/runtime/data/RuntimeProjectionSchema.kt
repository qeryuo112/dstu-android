package com.deepstudent.services.runtime.data

import com.deepstudent.platform.database.SqlOperations

/**
 * Local projection tables for runtime tasks. Every row is keyed by
 * `(task_id, generation, record_id)`, so two generations of one task and two tasks that reuse a
 * record key never collide. `cursor` always points at the `runtime_events` row written in the same
 * transaction.
 */
internal object RuntimeProjectionSchema {
    fun create(database: SqlOperations) {
        database.execute(AUDIT)
        database.execute(APPROVALS)
        database.execute(DUTIES)
        // Approvals and duties are keyed by their primary key (task_id, generation, record_id);
        // only the audit table needs a separate index for cursor-ordered pages.
        database.execute("CREATE INDEX runtime_audit_task_cursor ON runtime_audit(task_id, generation, cursor)")
    }

    private val AUDIT = """
        CREATE TABLE runtime_audit (
            cursor INTEGER PRIMARY KEY REFERENCES runtime_events(cursor),
            task_id TEXT NOT NULL REFERENCES runtime_tasks(task_id),
            generation INTEGER NOT NULL CHECK (generation > 0),
            record_id TEXT NOT NULL,
            action TEXT NOT NULL,
            code TEXT NOT NULL,
            at TEXT NOT NULL,
            UNIQUE(task_id, generation, record_id, action)
        )
    """.trimIndent()

    private val APPROVALS = """
        CREATE TABLE runtime_approvals (
            task_id TEXT NOT NULL REFERENCES runtime_tasks(task_id),
            generation INTEGER NOT NULL CHECK (generation > 0),
            record_id TEXT NOT NULL,
            operation TEXT NOT NULL,
            state TEXT NOT NULL CHECK (state IN ('PENDING', 'APPROVED', 'DENIED', 'CANCELLED')),
            requested_at TEXT NOT NULL,
            updated_at TEXT NOT NULL,
            cursor INTEGER NOT NULL REFERENCES runtime_audit(cursor),
            PRIMARY KEY(task_id, generation, record_id)
        )
    """.trimIndent()

    private val DUTIES = """
        CREATE TABLE runtime_duties (
            task_id TEXT NOT NULL REFERENCES runtime_tasks(task_id),
            generation INTEGER NOT NULL CHECK (generation > 0),
            record_id TEXT NOT NULL,
            purpose TEXT NOT NULL,
            registered_at TEXT NOT NULL,
            released_at TEXT,
            cursor INTEGER NOT NULL REFERENCES runtime_audit(cursor),
            PRIMARY KEY(task_id, generation, record_id)
        )
    """.trimIndent()
}

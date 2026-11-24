package com.deepstudent.services.runtime.data

import com.deepstudent.platform.database.SqlDatabase

/**
 * runtime.db layout versions:
 * - v1: tasks + events.
 * - v2: adds `runtime_tasks.confirmation_required`.
 * - v3: adds the approval/audit/duty projections served by [SqlTaskLedger]'s RuntimeProjectionStore.
 * - v4: adds optional non-secret capability bindings to approval rows.
 *
 * A new file starts directly at v4; existing v1/v2 files are upgraded in place inside the same
 * transaction, so a failed upgrade leaves the previous version untouched.
 */
internal object RuntimeSchema {
    fun initialize(database: SqlDatabase) {
        database.transaction {
            execute("CREATE TABLE IF NOT EXISTS runtime_schema (version INTEGER NOT NULL)")
            val versions = query("SELECT version FROM runtime_schema") { it.long(0) }
            check(versions.isEmpty() || versions in SUPPORTED) {
                "unsupported runtime database version"
            }
            if (versions.isEmpty()) {
                execute(TASKS)
                execute(EVENTS)
                execute("CREATE INDEX runtime_events_task_cursor ON runtime_events(task_id, cursor)")
                RuntimeProjectionSchema.create(this)
                RuntimeProjectionSchema.addCapabilityBindings(this)
                execute("INSERT INTO runtime_schema(version) VALUES (4)")
            } else {
                var version = versions.single()
                if (version == 1L) {
                    execute("ALTER TABLE runtime_tasks ADD COLUMN confirmation_required INTEGER NOT NULL DEFAULT 0")
                    version = 2L
                }
                if (version == 2L) {
                    RuntimeProjectionSchema.create(this)
                    version = 3L
                }
                if (version == 3L) {
                    RuntimeProjectionSchema.addCapabilityBindings(this)
                    version = 4L
                }
                execute("UPDATE runtime_schema SET version = ?", listOf(integer(version)))
            }
        }
    }

    private val SUPPORTED = listOf(listOf(1L), listOf(2L), listOf(3L), listOf(4L))

    private val TASKS = """
        CREATE TABLE runtime_tasks (
            task_id TEXT PRIMARY KEY NOT NULL,
            kind TEXT NOT NULL,
            payload TEXT NOT NULL,
            correlation_id TEXT,
            dedupe_key TEXT,
            state TEXT NOT NULL,
            generation INTEGER NOT NULL,
            owner_epoch INTEGER NOT NULL,
            owner_id TEXT,
            lease_until TEXT,
            checkpoint TEXT,
            cancel_requested INTEGER NOT NULL,
            receipt TEXT,
            business_fact_id TEXT UNIQUE,
            error_code TEXT,
            created_at TEXT NOT NULL,
            updated_at TEXT NOT NULL,
            cursor INTEGER NOT NULL,
            confirmation_required INTEGER NOT NULL DEFAULT 0,
            UNIQUE(kind, dedupe_key)
        )
    """.trimIndent()

    private val EVENTS = """
        CREATE TABLE runtime_events (
            cursor INTEGER PRIMARY KEY AUTOINCREMENT,
            task_id TEXT NOT NULL REFERENCES runtime_tasks(task_id),
            generation INTEGER NOT NULL,
            type TEXT NOT NULL,
            state TEXT NOT NULL,
            at TEXT NOT NULL
        )
    """.trimIndent()
}

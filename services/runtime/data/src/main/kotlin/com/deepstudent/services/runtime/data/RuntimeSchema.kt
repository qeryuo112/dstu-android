package com.deepstudent.services.runtime.data

import com.deepstudent.platform.database.SqlDatabase

internal object RuntimeSchema {
    fun initialize(database: SqlDatabase) {
        database.transaction {
            execute("CREATE TABLE IF NOT EXISTS runtime_schema (version INTEGER NOT NULL)")
            val versions = query("SELECT version FROM runtime_schema") { it.long(0) }
            check(versions.isEmpty() || versions == listOf(1L) || versions == listOf(2L)) {
                "unsupported runtime database version"
            }
            if (versions.isEmpty()) {
                execute(TASKS)
                execute(EVENTS)
                execute("CREATE INDEX runtime_events_task_cursor ON runtime_events(task_id, cursor)")
                execute("INSERT INTO runtime_schema(version) VALUES (2)")
            } else if (versions == listOf(1L)) {
                execute("ALTER TABLE runtime_tasks ADD COLUMN confirmation_required INTEGER NOT NULL DEFAULT 0")
                execute("UPDATE runtime_schema SET version = 2")
            }
        }
    }

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

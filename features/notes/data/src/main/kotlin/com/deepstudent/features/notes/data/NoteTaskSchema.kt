package com.deepstudent.features.notes.data

import com.deepstudent.platform.database.SqlDatabase

/** Android-local completion facts, outside the frozen shared migration-managed object scope. */
internal object NoteTaskSchema {
    fun initialize(database: SqlDatabase) = database.transaction {
        execute("CREATE TABLE IF NOT EXISTS __android_notes_task_schema(version INTEGER NOT NULL)")
        val versions = query("SELECT version FROM __android_notes_task_schema") { it.long(0) }
        check(versions.isEmpty() || versions == listOf(1L)) { "unsupported notes task schema" }
        if (versions.isEmpty()) {
            execute("""CREATE TABLE __android_notes_task_facts(
                fact_id TEXT PRIMARY KEY NOT NULL,task_id TEXT NOT NULL,generation INTEGER NOT NULL CHECK(generation>0),
                request_hash TEXT NOT NULL,note_id TEXT NOT NULL,committed_at TEXT NOT NULL,
                UNIQUE(task_id,generation))""".trimIndent())
            execute("""CREATE TABLE __android_notes_task_ack(
                consumer_id TEXT NOT NULL,fact_id TEXT NOT NULL REFERENCES __android_notes_task_facts(fact_id),
                PRIMARY KEY(consumer_id,fact_id))""".trimIndent())
            execute("INSERT INTO __android_notes_task_schema VALUES(1)")
        }
    }
}

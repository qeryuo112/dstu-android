package com.deepstudent.features.chat.data

import com.deepstudent.platform.database.SqlDatabase

/** Android-private chat turn ledger. It never reuses the frozen subagent completion_outbox. */
internal object ChatTurnSchema {
    const val USAGE_CONSUMER = "usage"
    const val RUNTIME_CONSUMER = "runtime.recovery"
    const val FACT_SCHEMA = "chat_turn"

    fun initialize(database: SqlDatabase) = database.transaction {
        execute("CREATE TABLE IF NOT EXISTS __android_chat_turn_schema(database TEXT PRIMARY KEY,version INTEGER NOT NULL)")
        val versions = query("SELECT version FROM __android_chat_turn_schema WHERE database=?", listOf(text(FACT_SCHEMA))) { it.long(0) }
        check(versions.isEmpty() || versions == listOf(1L)) { "unsupported chat turn schema" }
        if (versions.isEmpty()) {
            execute(
                """CREATE TABLE __android_chat_turn_facts(
                fact_id TEXT PRIMARY KEY NOT NULL,task_id TEXT NOT NULL,generation INTEGER NOT NULL CHECK(generation>0),
                state TEXT NOT NULL CHECK(state IN ('success','error','cancelled')),
                session_id TEXT NOT NULL,assistant_id TEXT NOT NULL,block_id TEXT NOT NULL,
                receipt TEXT NOT NULL,committed_at TEXT NOT NULL,
                UNIQUE(task_id,generation))
                """.trimIndent(),
            )
            execute(
                """CREATE TABLE __android_chat_turn_acks(
                consumer_id TEXT NOT NULL,fact_id TEXT NOT NULL REFERENCES __android_chat_turn_facts(fact_id),
                acknowledged_at TEXT NOT NULL,PRIMARY KEY(consumer_id,fact_id))
                """.trimIndent(),
            )
            execute(
                """CREATE TABLE __android_chat_turn_usage(
                fact_id TEXT PRIMARY KEY NOT NULL REFERENCES __android_chat_turn_facts(fact_id),
                task_id TEXT NOT NULL,generation INTEGER NOT NULL CHECK(generation>0),
                session_id TEXT NOT NULL,variant_id TEXT NOT NULL,
                provider TEXT NOT NULL,model TEXT NOT NULL,api_config_id TEXT NOT NULL,adapter TEXT NOT NULL,
                prompt_tokens INTEGER,completion_tokens INTEGER,total_tokens INTEGER,reasoning_tokens INTEGER,
                cached_tokens INTEGER,cache_write_tokens INTEGER,status TEXT NOT NULL,error_message TEXT,
                created_at TEXT NOT NULL,UNIQUE(task_id,generation))
                """.trimIndent(),
            )
            execute("INSERT INTO __android_chat_turn_schema VALUES(?,1)", listOf(text(FACT_SCHEMA)))
        }
    }
}

internal fun SqlDatabase.ensureChatTurnSchema() {
    ChatTurnSchema.initialize(this)
}

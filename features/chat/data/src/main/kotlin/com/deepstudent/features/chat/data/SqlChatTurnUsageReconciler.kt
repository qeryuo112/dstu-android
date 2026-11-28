package com.deepstudent.features.chat.data

import com.deepstudent.core.common.id.TaskId
import com.deepstudent.core.common.logging.LogLevel
import com.deepstudent.core.common.logging.LogRecord
import com.deepstudent.core.common.logging.Logger
import com.deepstudent.core.common.logging.NoopLogger
import com.deepstudent.core.common.time.ClockChecks
import com.deepstudent.platform.database.SqlDatabase
import com.deepstudent.platform.database.SqlValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.time.Instant

/** Durable usage consumer. Both database suppliers are lazy and may not be evaluated in construction. */
class SqlChatTurnUsageReconciler(
    private val chatDatabase: () -> SqlDatabase,
    private val usageDatabase: () -> SqlDatabase,
    private val clock: () -> Instant = { Instant.now() },
    private val logger: Logger = NoopLogger,
) {
    constructor(chatDatabase: SqlDatabase, usageDatabase: SqlDatabase, logger: Logger = NoopLogger) :
        this({ chatDatabase }, { usageDatabase }, { Instant.now() }, logger)

    suspend fun pending(consumerId: String = ChatTurnSchema.USAGE_CONSUMER, limit: Int = 100): List<ChatTurnUsageRow> =
        operation("pending") { _ ->
            val db = chatDatabase()
            db.readSnapshot {
                readChatTurnPendingFacts(this, consumerId, limit).mapNotNull { fact ->
                    readChatTurnUsageCandidate(this, fact.taskId, fact.generation)
                }
            }
        }

    /** Writes a stable usage row first, then acknowledges only the usage consumer.
     * Incomplete provider counters remain queryable in the private outbox.
     */
    suspend fun reconcile(taskId: TaskId, generation: Long): Boolean = operation("reconcile") { context ->
        val sharedDb = chatDatabase()
        val row = sharedDb.readSnapshot {
            readChatTurnFact(this, taskId, generation)?.let { readChatTurnUsageCandidate(this, taskId, generation) }
        } ?: return@operation false
        if (!row.counts.canWriteSharedUsage()) return@operation false
        usageDatabase().transaction {
            context.ensureActive()
            execute(USAGE_INSERT, usageInsertArgs(row))
            context.ensureActive()
        }
        sharedDb.transaction {
            context.ensureActive()
            acknowledgeFact(ChatTurnSchema.USAGE_CONSUMER, row.identity.factId, clock())
            context.ensureActive()
        }
        true
    }

    private suspend fun <T> operation(stage: String, body: suspend (kotlin.coroutines.CoroutineContext) -> T): T =
        withContext(Dispatchers.IO) {
            val started = System.nanoTime()
            try {
                val active = currentCoroutineContext()
                active.ensureActive()
                val result = body(active)
                trace(stage, "completed", started)
                result
            } catch (failure: CancellationException) {
                trace(stage, "cancelled", started)
                throw failure
            } catch (failure: Exception) {
                trace(stage, "failed", started)
                throw failure
            }
        }

    private fun trace(stage: String, outcome: String, started: Long) {
        try {
            logger.log(
                LogRecord(
                    level = if (outcome == "failed") LogLevel.WARN else LogLevel.INFO,
                    event = "chat.turn_usage.$stage",
                    fields = com.deepstudent.core.common.json.JsonValue.Object.Builder()
                        .put("owner", "features/chat/data").put("database", "chat_v2+llm_usage")
                        .put("stage", stage).put("outcome", outcome)
                        .put("duration", ClockChecks.monotonicElapsedMillis(started, System.nanoTime()) ?: 0L).build(),
                ),
            )
        } catch (_: Exception) { }
    }
}
private fun ChatTurnUsageCounts.canWriteSharedUsage(): Boolean = promptTokens != null && completionTokens != null && totalTokens != null

private fun Long?.toSql(): SqlValue = this?.let(SqlValue::Integer) ?: SqlValue.Null

private fun usageInsertArgs(row: ChatTurnUsageRow): List<SqlValue> = listOf(
    text("usage_${java.util.UUID.nameUUIDFromBytes("${row.identity.taskId.value}:${row.identity.generation}:usage".encodeToByteArray())}"),
    text(
        row.outcome.createdAt,
    ),
    text(row.provider.provider), text(row.provider.model), text(row.provider.adapter), text(row.provider.configId),
    SqlValue.Integer(checkNotNull(row.counts.promptTokens)), SqlValue.Integer(checkNotNull(row.counts.completionTokens)),
    SqlValue.Integer(checkNotNull(row.counts.totalTokens)),
    row.counts.reasoningTokens.toSql(), row.counts.cachedTokens.toSql(), row.counts.cacheWriteTokens.toSql(),
    text(row.identity.sessionId), text(row.identity.variantId), text(row.identity.taskId.value), text(row.outcome.status),
    text(row.outcome.errorMessage),
)

private const val USAGE_INSERT = """INSERT INTO llm_usage_logs(
id,timestamp,provider,model,adapter,api_config_id,prompt_tokens,completion_tokens,total_tokens,
reasoning_tokens,cached_tokens,cache_write_tokens,token_source,caller_type,session_id,
variant_id,run_id,status,error_message) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,'api','chat_v2',?,?,?,?,?)
ON CONFLICT(id) DO NOTHING
"""

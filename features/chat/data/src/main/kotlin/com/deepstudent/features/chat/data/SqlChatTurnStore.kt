package com.deepstudent.features.chat.data

import com.deepstudent.core.common.id.TaskId
import com.deepstudent.core.common.logging.LogLevel
import com.deepstudent.core.common.logging.LogRecord
import com.deepstudent.core.common.logging.Logger
import com.deepstudent.core.common.logging.NoopLogger
import com.deepstudent.core.common.time.ClockChecks
import com.deepstudent.features.chat.api.BlockDetails
import com.deepstudent.features.chat.api.BlockTiming
import com.deepstudent.features.chat.api.ChatBlock
import com.deepstudent.features.chat.api.ChatMessage
import com.deepstudent.features.chat.api.ChatSession
import com.deepstudent.features.chat.api.ChatTurnIds
import com.deepstudent.features.chat.api.ChatTurnInFlight
import com.deepstudent.features.chat.api.ChatTurnRecoveryPort
import com.deepstudent.features.chat.api.ChatTurnRequest
import com.deepstudent.features.chat.api.ChatTurnUsage
import com.deepstudent.features.chat.api.ChatTurnWritePort
import com.deepstudent.platform.database.SqlDatabase
import com.deepstudent.platform.database.SqlOperations
import com.deepstudent.platform.database.SqlValue
import com.deepstudent.services.runtime.api.BusinessCompletion
import com.deepstudent.services.runtime.api.BusinessCompletionSource
import com.deepstudent.services.runtime.api.RuntimeState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.UUID

/** The single chat_v2 owner for one turn, including the Android-private terminal outbox. */
class SqlChatTurnStore(
    private val database: () -> SqlDatabase,
    private val clock: () -> Instant = { Instant.now() },
    private val logger: Logger = NoopLogger,
) : ChatTurnWritePort,
    ChatTurnRecoveryPort,
    BusinessCompletionSource {
    constructor(database: SqlDatabase, logger: Logger = NoopLogger) : this({ database }, { Instant.now() }, logger)

    init {
        database().ensureChatTurnSchema()
    }

    override suspend fun begin(request: ChatTurnRequest, startedAt: Instant) = operation("begin") { context ->
        database().transaction {
            context.ensureActive()
            val ids = ChatTurnIds.of(request.taskId, request.generation)
            val at = startedAt.toEpochMilli()
            putSession(
                ChatSession(
                    id = request.sessionId,
                    mode = "general_chat",
                    createdAt = databaseTimestamp(startedAt),
                    updatedAt = databaseTimestamp(startedAt),
                ),
            )
            putMessage(ChatMessage(ids.userId, request.sessionId, "user", listOf(userBlockId(request)), at))
            putBlock(
                ChatBlock(
                    userBlockId(request),
                    ids.userId,
                    "content",
                    "success",
                    0,
                    BlockDetails(content = request.prompt),
                ),
                request.sessionId,
            )
            putMessage(ChatMessage(ids.assistantId, request.sessionId, "assistant", listOf(ids.blockId), at))
            putBlock(
                ChatBlock(
                    ids.blockId,
                    ids.assistantId,
                    "content",
                    "running",
                    0,
                    timing = BlockTiming(startedAt = at),
                ),
                request.sessionId,
            )
            context.ensureActive()
        }
    }

    override suspend fun append(request: ChatTurnRequest, text: String, firstChunkAt: Instant?, updatedAt: Instant) =
        operation("append") { context ->
            database().transaction {
                context.ensureActive()
                val ids = ChatTurnIds.of(request.taskId, request.generation)
                val existingStart = query("SELECT started_at FROM chat_v2_blocks WHERE id=?", listOf(text(ids.blockId))) {
                    if (it.isNull(0)) null else it.long(0)
                }.singleOrNull()
                putBlock(
                    ChatBlock(
                        ids.blockId,
                        ids.assistantId,
                        "content",
                        "running",
                        0,
                        details = BlockDetails(content = text),
                        timing = BlockTiming(
                            startedAt = existingStart ?: updatedAt.toEpochMilli(),
                            firstChunkAt = firstChunkAt?.toEpochMilli(),
                        ),
                    ),
                    request.sessionId,
                )
                context.ensureActive()
            }
        }

    override suspend fun complete(request: ChatTurnRequest, text: String, usage: ChatTurnUsage?, endedAt: Instant) =
        finish(request, text, usage, endedAt, "success")
    override suspend fun fail(request: ChatTurnRequest, text: String, usage: ChatTurnUsage?, endedAt: Instant) =
        finish(request, text, usage, endedAt, "error")
    override suspend fun cancel(request: ChatTurnRequest, text: String, usage: ChatTurnUsage?, endedAt: Instant) =
        finish(request, text, usage, endedAt, "cancelled")

    override suspend fun interrupt(request: ChatTurnRequest, endedAt: Instant) {
        settleInterrupted(request.taskId, request.generation, request.sessionId, endedAt)
    }

    override suspend fun loadInFlight(taskId: TaskId, sessionId: String): ChatTurnInFlight? =
        loadInFlight(taskId, generation = 1L, sessionId = sessionId)

    override suspend fun loadInFlight(taskId: TaskId, generation: Long, sessionId: String): ChatTurnInFlight? =
        operation("load_in_flight") { context ->
            database().readSnapshot { readInFlight(database(), taskId, generation, sessionId) }
        }

    /**
     * Settles only the committed block. A terminal fact is authoritative when present; a block
     * alone is not a business fact. Returns null when nothing durable exists.
     */
    override suspend fun settleInterrupted(taskId: TaskId, generation: Long, sessionId: String, at: Instant): RuntimeState? =
        operation("settle_interrupted") { context ->
            database().transaction {
                context.ensureActive()
                readChatTurnFact(this, taskId, generation)?.let { return@transaction it.completion.state }
                val existing = readInFlight(database(), taskId, generation, sessionId) ?: return@transaction null
                if (existing.blockStatus !in setOf("pending", "running")) return@transaction null
                val ids = ChatTurnIds.of(taskId, generation)
                putBlock(
                    ChatBlock(
                        ids.blockId,
                        ids.assistantId,
                        "content",
                        "error",
                        0,
                        details = BlockDetails(content = existing.text, error = INTERRUPTED_ERROR),
                        timing = BlockTiming(
                            startedAt = blockStartedAt(ids.blockId) ?: at.toEpochMilli(),
                            endedAt = at.toEpochMilli(),
                            firstChunkAt = blockFirstChunkAt(ids.blockId),
                        ),
                    ),
                    sessionId,
                )
                context.ensureActive()
                RuntimeState.INTERRUPTED
            }
        }

    override suspend fun find(taskId: TaskId, generation: Long): BusinessCompletion? = operation("find") { context ->
        val db = database()
        db.readSnapshot { readChatTurnFact(this, taskId, generation)?.completion }
    }

    override suspend fun pending(consumerId: String, limit: Int): List<BusinessCompletion> = operation("pending") { context ->
        val db = database()
        db.readSnapshot { readChatTurnPendingFacts(this, consumerId, limit).map(ChatTurnFact::completion) }
    }

    override suspend fun acknowledge(consumerId: String, factId: String) = operation("ack") { context ->
        database().transaction {
            context.ensureActive()
            acknowledgeFact(consumerId, factId, clock())
            context.ensureActive()
        }
    }

    private suspend fun finish(request: ChatTurnRequest, text: String, usage: ChatTurnUsage?, endedAt: Instant, businessStatus: String) =
        operation("finish_$businessStatus") { context ->
            database().transaction {
                context.ensureActive()
                if (existingFactConflicts(request, businessStatus)) return@transaction
                putTerminalRows(request, text, endedAt, businessStatus)
                putTerminalFact(request, usage, endedAt, businessStatus)
                context.ensureActive()
            }
        }

    private fun SqlOperations.existingFactConflicts(request: ChatTurnRequest, businessStatus: String): Boolean {
        val existing = readChatTurnFact(this, request.taskId, request.generation) ?: return false
        if (existing.status != businessStatus) conflict()
        return true
    }

    private fun SqlOperations.putTerminalRows(request: ChatTurnRequest, text: String, endedAt: Instant, businessStatus: String) {
        val ids = ChatTurnIds.of(request.taskId, request.generation)
        val at = endedAt.toEpochMilli()
        val startedAt = sessionCreatedAt(request.sessionId) ?: endedAt
        putSession(ChatSession(request.sessionId, "general_chat", databaseTimestamp(startedAt), databaseTimestamp(endedAt)))
        putMessage(ChatMessage(ids.userId, request.sessionId, "user", listOf(userBlockId(request)), messageTimestamp(ids.userId, at)))
        putBlock(
            ChatBlock(
                userBlockId(request),
                ids.userId,
                "content",
                "success",
                0,
                BlockDetails(content = request.prompt),
            ),
            request.sessionId,
        )
        putMessage(ChatMessage(ids.assistantId, request.sessionId, "assistant", listOf(ids.blockId), messageTimestamp(ids.assistantId, at)))
        val blockStatus = if (businessStatus == "success") "success" else "error"
        putBlock(
            ChatBlock(
                ids.blockId,
                ids.assistantId,
                "content",
                blockStatus,
                0,
                details = BlockDetails(content = text, error = if (blockStatus == "error") errorFor(businessStatus) else null),
                timing = BlockTiming(
                    startedAt = blockStartedAt(ids.blockId) ?: at,
                    endedAt = at,
                    firstChunkAt = blockFirstChunkAt(ids.blockId),
                ),
            ),
            request.sessionId,
        )
    }

    private fun SqlOperations.putTerminalFact(request: ChatTurnRequest, usage: ChatTurnUsage?, endedAt: Instant, businessStatus: String) {
        val terminal = ChatTurnFact(
            factId(request),
            request.taskId,
            request.generation,
            businessStatus,
            request.sessionId,
            receipt(request),
            endedAt,
        )
        putFact(terminal)
        putUsageCandidate(terminal, usage)
    }

    private fun SqlOperations.sessionCreatedAt(sessionId: String): Instant? =
        query("SELECT created_at FROM chat_v2_sessions WHERE id=?", listOf(text(sessionId))) {
            Instant.parse(it.text(0).replace("+00:00", "Z"))
        }.singleOrNull()

    private fun SqlOperations.messageTimestamp(messageId: String, fallback: Long): Long =
        query("SELECT timestamp FROM chat_v2_messages WHERE id=?", listOf(text(messageId))) {
            it.long(0)
        }.singleOrNull() ?: fallback

    private fun SqlOperations.blockStartedAt(blockId: String): Long? =
        query("SELECT started_at FROM chat_v2_blocks WHERE id=?", listOf(text(blockId))) {
            if (it.isNull(0)) null else it.long(0)
        }.singleOrNull()

    private fun SqlOperations.blockFirstChunkAt(blockId: String): Long? =
        query("SELECT first_chunk_at FROM chat_v2_blocks WHERE id=?", listOf(text(blockId))) {
            if (it.isNull(0)) null else it.long(0)
        }.singleOrNull()

    private fun readInFlight(db: SqlDatabase, taskId: TaskId, generation: Long?, sessionId: String): ChatTurnInFlight? {
        val blockId = if (generation == null) {
            db.query(
                "SELECT block_id FROM __android_chat_turn_facts WHERE task_id=? AND session_id=? ORDER BY generation DESC LIMIT 1",
                listOf(text(taskId.value), text(sessionId)),
            ) { it.text(0) }.singleOrNull()
                ?: newestBlockId(db, sessionId)
        } else {
            ChatTurnIds.of(taskId, generation).blockId
        }
        return db.query(
            """SELECT b.content,b.status,b.error FROM chat_v2_blocks b
            JOIN chat_v2_messages m ON m.id=b.message_id
            WHERE m.session_id=? AND b.id=? LIMIT 1
            """.trimIndent(),
            listOf(text(sessionId), text(blockId)),
        ) { row ->
            ChatTurnInFlight(if (row.isNull(0)) "" else row.text(0), row.text(1), if (row.isNull(2)) null else row.text(2))
        }.singleOrNull()
    }

    private fun newestBlockId(db: SqlDatabase, sessionId: String): String? = db.query(
        "SELECT b.id FROM chat_v2_blocks b JOIN chat_v2_messages m ON m.id=b.message_id " +
            "WHERE m.session_id=? AND b.block_type='content' AND m.role='assistant' ORDER BY m.timestamp DESC,b.block_index ASC LIMIT 1",
        listOf(text(sessionId)),
    ) { it.text(0) }.singleOrNull()

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
        val record = LogRecord(
            level = if (outcome == "failed") LogLevel.WARN else LogLevel.INFO,
            event = "chat.turn.$stage",
            fields = com.deepstudent.core.common.json.JsonValue.Object.Builder()
                .put("owner", "features/chat/data").put("database", "chat_v2").put("stage", stage)
                .put("outcome", outcome).put("duration", ClockChecks.monotonicElapsedMillis(started, System.nanoTime()) ?: 0L)
                .build(),
        )
        try {
            logger.log(record)
        } catch (_: Exception) { }
    }

    private companion object {
        const val INTERRUPTED_ERROR = "chat turn interrupted"
        const val FAILED_ERROR = "chat turn failed"
        const val CANCELLED_ERROR = "chat turn cancelled"
        fun conflict(): Nothing = throw IllegalStateException("conflicting chat turn terminal fact")
        fun errorFor(status: String): String = if (status == "cancelled") CANCELLED_ERROR else FAILED_ERROR
    }
}

private fun userBlockId(request: ChatTurnRequest): String =
    "blk_${UUID.nameUUIDFromBytes("${request.taskId.value}:${request.generation}:user:block".encodeToByteArray())}"
private fun factId(request: ChatTurnRequest): String =
    "chat_turn:${UUID.nameUUIDFromBytes("${request.taskId.value}:${request.generation}:fact".encodeToByteArray())}"
private fun receipt(request: ChatTurnRequest): String =
    UUID.nameUUIDFromBytes("${request.taskId.value}:${request.generation}:receipt".encodeToByteArray()).toString()
private fun databaseTimestamp(time: Instant): String = time.toString().removeSuffix("Z") + "+00:00"

internal fun SqlOperations.putUsageCandidate(fact: ChatTurnFact, usage: ChatTurnUsage?) {
    if (usage == null) return
    execute(
        """INSERT OR IGNORE INTO __android_chat_turn_usage(
        fact_id,task_id,generation,session_id,variant_id,provider,model,api_config_id,adapter,
        prompt_tokens,completion_tokens,total_tokens,reasoning_tokens,cached_tokens,cache_write_tokens,
        status,error_message,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
        """.trimIndent(),
        listOf(
            text(fact.factId), text(fact.taskId.value), SqlValue.Integer(fact.generation),
            text(fact.sessionId), text(ChatTurnIds.of(fact.taskId, fact.generation).assistantId),
            text(usage.metadata.provider), text(usage.metadata.model), text(usage.metadata.configId),
            text(usage.metadata.adapter), usage.promptTokens.toSql(), usage.completionTokens.toSql(),
            reportedTotal(usage).toSql(), usage.reasoningTokens.toSql(), usage.cachedTokens.toSql(),
            usage.cacheWriteTokens.toSql(), text(fact.status),
            text(usageError(fact.status)), text(fact.committedAt.toString()),
        ),
    )
}

internal fun readChatTurnUsageCandidate(
    scope: com.deepstudent.platform.database.SqlReadOperations,
    taskId: TaskId,
    generation: Long,
): ChatTurnUsageRow? = scope.query(
    """SELECT fact_id,task_id,generation,provider,model,api_config_id,adapter,prompt_tokens,completion_tokens,total_tokens,
        reasoning_tokens,cached_tokens,cache_write_tokens,status,error_message,created_at,session_id,variant_id
        FROM __android_chat_turn_usage WHERE task_id=? AND generation=?
    """.trimIndent(),
    listOf(text(taskId.value), SqlValue.Integer(generation)),
) { row ->
    ChatTurnUsageRow(
        identity = ChatTurnUsageIdentity(row.text(0), TaskId.parse(row.text(1)), row.long(2), row.text(16), row.text(17)),
        provider = ChatTurnUsageProvider(row.text(3), row.text(4), row.text(5), row.text(6)),
        counts = ChatTurnUsageCounts(row.optLong(7), row.optLong(8), row.optLong(9), row.optLong(10), row.optLong(11), row.optLong(12)),
        outcome = ChatTurnUsageOutcome(row.text(13), row.optText(14), row.text(15)),
    )
}.singleOrNull()

data class ChatTurnUsageRow(
    val identity: ChatTurnUsageIdentity,
    val provider: ChatTurnUsageProvider,
    val counts: ChatTurnUsageCounts,
    val outcome: ChatTurnUsageOutcome,
)

data class ChatTurnUsageIdentity(val factId: String, val taskId: TaskId, val generation: Long, val sessionId: String, val variantId: String)
data class ChatTurnUsageProvider(val provider: String, val model: String, val configId: String, val adapter: String)
data class ChatTurnUsageCounts(
    val promptTokens: Long?,
    val completionTokens: Long?,
    val totalTokens: Long?,
    val reasoningTokens: Long?,
    val cachedTokens: Long?,
    val cacheWriteTokens: Long?,
)
data class ChatTurnUsageOutcome(val status: String, val errorMessage: String?, val createdAt: String)

private fun com.deepstudent.platform.database.SqlRow.optLong(index: Int): Long? = if (isNull(index)) null else long(index)
private fun com.deepstudent.platform.database.SqlRow.optText(index: Int): String? = if (isNull(index)) null else text(index)
private fun Long?.toSql() = this?.let(SqlValue::Integer) ?: SqlValue.Null
private fun usageError(status: String): String? = when (status) {
    "error" -> "chat turn failed"
    "cancelled" -> "chat turn cancelled"
    else -> null
}

private fun reportedTotal(usage: ChatTurnUsage): Long? {
    usage.totalTokens?.let { return it }
    val input = usage.promptTokens ?: return null
    val output = usage.completionTokens ?: return null
    return if (input <= Long.MAX_VALUE - output) input + output else null
}

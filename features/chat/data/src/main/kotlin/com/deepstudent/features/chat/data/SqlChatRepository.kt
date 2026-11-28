package com.deepstudent.features.chat.data

import com.deepstudent.core.common.id.CorrelationId
import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.core.common.logging.LogLevel
import com.deepstudent.core.common.logging.LogRecord
import com.deepstudent.core.common.logging.Logger
import com.deepstudent.core.common.logging.NoopLogger
import com.deepstudent.core.common.time.ClockChecks
import com.deepstudent.features.chat.api.ChatConversation
import com.deepstudent.features.chat.api.ChatRepository
import com.deepstudent.platform.database.SqlDatabase
import com.deepstudent.platform.database.SqlValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.coroutines.CoroutineContext

/** Borrows the main storage owner's migrated chat database lease. */
class SqlChatRepository(private val database: () -> SqlDatabase, private val logger: Logger = NoopLogger) : ChatRepository {
    constructor(database: SqlDatabase, logger: Logger = NoopLogger) : this({ database }, logger)

    /** Incremental writes and terminal facts use the same lease as conversation reads. */
    val turns: SqlChatTurnStore by lazy { SqlChatTurnStore(database, logger = logger) }

    override suspend fun load(sessionId: String): ChatConversation? = operation("load") {
        database().readSnapshot { readChat(sessionId) }
    }

    override suspend fun save(conversation: ChatConversation): Unit = operation("save") { context ->
        validateChat(conversation)
        context.ensureActive()
        database().transaction {
            context.ensureActive()
            putSession(conversation.session)
            conversation.messages.forEach { putMessage(it) }
            conversation.blocks.forEach { putBlock(it, conversation.session.id) }
            // Throw inside the transaction so cancellation rolls back the batch.
            context.ensureActive()
        }
    }

    private suspend fun <T> operation(stage: String, run: (CoroutineContext) -> T): T = withContext(Dispatchers.IO) {
        val trace = ChatRepositoryTrace(logger, stage)
        val context = currentCoroutineContext()
        try {
            context.ensureActive()
            val result = run(context)
            trace.emit("completed")
            result
        } catch (failure: CancellationException) {
            trace.emit("cancelled")
            throw failure
        } catch (failure: Exception) {
            trace.emit("failed")
            throw failure
        }
    }
}

private class ChatRepositoryTrace(private val logger: Logger, private val stage: String) {
    private val correlation = CorrelationId.parse(UUID.randomUUID().toString())
    private val started = System.nanoTime()

    fun emit(status: String) {
        val event = if (status == "completed") "chat.repository.$stage" else "chat.repository.${stage}_$status"
        val duration = ClockChecks.monotonicElapsedMillis(started, System.nanoTime()) ?: 0L
        val fields = JsonValue.Object.Builder().put("owner", "features/chat/data").put("database", "chat_v2")
            .put("stage", stage).put("status", status).put("durationMillis", duration).build()
        val record = LogRecord(
            level = if (status == "failed") LogLevel.WARN else LogLevel.INFO,
            event = event,
            correlationId = correlation,
            fields = fields,
        )
        try {
            logger.log(record)
        } catch (_: Exception) {
            // The transaction result remains authoritative if the log sink fails.
        }
    }
}

internal fun text(value: String?): SqlValue = value?.let(SqlValue::Text) ?: SqlValue.Null
internal fun integer(value: Long?): SqlValue = value?.let(SqlValue::Integer) ?: SqlValue.Null
internal fun json(value: JsonValue?): SqlValue = text(value?.literal())

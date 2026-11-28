package com.deepstudent.features.chat.backend.application

import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.core.common.logging.LogLevel
import com.deepstudent.core.common.logging.LogRecord
import com.deepstudent.core.common.logging.Logger
import com.deepstudent.core.common.logging.NoopLogger
import com.deepstudent.core.common.time.SystemWallClock
import com.deepstudent.core.common.time.WallClock
import com.deepstudent.features.chat.api.ChatTurnEvent
import com.deepstudent.features.chat.api.ChatTurnEventSink
import com.deepstudent.features.chat.api.ChatTurnIds
import com.deepstudent.features.chat.api.ChatTurnPayloadDecoder
import com.deepstudent.features.chat.api.ChatTurnRequest
import com.deepstudent.features.chat.api.ChatTurnTransport
import com.deepstudent.features.chat.api.ChatTurnUsage
import com.deepstudent.features.chat.api.ChatTurnWritePort
import com.deepstudent.services.runtime.api.RuntimeTaskContext
import com.deepstudent.services.runtime.api.RuntimeTaskHandler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant

/** Concrete transcript state shared with the runtime checkpoint and emitted to observers. */
data class ChatTurnTranscript(
    val taskId: String,
    val generation: Long,
    val sessionId: String,
    val userId: String,
    val assistantId: String,
    val blockId: String,
    val text: String,
    val phase: ChatTurnPhase,
) {
    val checkpoint: JsonValue.Object get() = JsonValue.Object.Builder()
        .put("taskId", taskId).put("generation", generation).put("sessionId", sessionId)
        .put("userId", userId).put("assistantId", assistantId).put("blockId", blockId)
        .put("text", text).put("phase", phase.name.lowercase()).build()

    override fun toString(): String = "ChatTurnTranscript(taskId=$taskId,generation=$generation,phase=$phase)"
}

enum class ChatTurnPhase { RUNNING, COMPLETED, FAILED, CANCELLED, INTERRUPTED }

/** Observer receives committed transcript snapshots; it never owns execution or cancellation. */
fun interface ChatTurnObserver {
    fun publish(transcript: ChatTurnTranscript)
}

/**
 * Durable single-turn orchestration. Chat rows are committed before the runtime checkpoint that
 * points at them, so a crash between the two leaves recoverable shared text.
 */
class ChatTurnPipeline(
    private val transport: ChatTurnTransport,
    private val writes: ChatTurnWritePort,
    private val observer: ChatTurnObserver = ChatTurnObserver { },
    private val logger: Logger = NoopLogger,
    private val clock: WallClock = SystemWallClock,
) : RuntimeTaskHandler {
    override suspend fun execute(context: RuntimeTaskContext, payload: JsonValue) {
        val request = ChatTurnPayloadDecoder.decode(payload, context.taskId, context.generation)
        val turn = ActiveTurn(request, ChatTurnIds.of(request.taskId, request.generation), clock.now())
        try {
            writes.begin(request, turn.startedAt)
            publish(context, turn, ChatTurnPhase.RUNNING)
            transport.collect(request, ChatTurnEventSink { event -> consume(context, turn, event) })
            check(turn.completed) { "chat transport ended without completion" }
            currentCoroutineContext().ensureActive()
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { settleCancellation(context, turn) }
            throw cancelled
        } catch (failure: Exception) {
            withContext(NonCancellable) {
                writes.fail(request, turn.text, turn.usage, clock.now())
                publishTerminal(context, turn, ChatTurnPhase.FAILED)
            }
            throw failure
        }

        // Once finalization starts, the committed fact wins a concurrent cancel or checkpoint failure.
        withContext(NonCancellable) {
            writes.complete(request, turn.text, turn.usage, clock.now())
            publishTerminal(context, turn, ChatTurnPhase.COMPLETED)
        }
    }

    private suspend fun consume(context: RuntimeTaskContext, turn: ActiveTurn, event: ChatTurnEvent) {
        currentCoroutineContext().ensureActive()
        check(!turn.completed) { "event after chat completion" }
        when (event) {
            is ChatTurnEvent.Text -> {
                require(turn.text.length + event.delta.length <= MAX_TEXT_LENGTH) { "chat text budget exceeded" }
                val updatedAt = maxOf(turn.startedAt, clock.now())
                val firstChunk = turn.firstChunk ?: updatedAt.also { turn.firstChunk = it }
                turn.text += event.delta
                writes.append(turn.request, turn.text, firstChunk, updatedAt)
                publish(context, turn, ChatTurnPhase.RUNNING)
            }

            is ChatTurnEvent.Usage -> turn.usage = event.usage

            ChatTurnEvent.Completed -> turn.completed = true
        }
    }

    private suspend fun settleCancellation(context: RuntimeTaskContext, turn: ActiveTurn) {
        val endedAt = maxOf(turn.startedAt, clock.now())
        val phase = if (context.cancelRequested) {
            writes.cancel(turn.request, turn.text, turn.usage, endedAt)
            ChatTurnPhase.CANCELLED
        } else {
            writes.interrupt(turn.request, endedAt)
            ChatTurnPhase.INTERRUPTED
        }
        publishTerminal(context, turn, phase)
    }

    private suspend fun publish(context: RuntimeTaskContext, turn: ActiveTurn, phase: ChatTurnPhase) {
        val transcript = transcript(turn.request, turn.ids, turn.text, phase)
        context.checkpoint(transcript.checkpoint)
        runCatching { observer.publish(transcript) }
    }

    private suspend fun publishTerminal(context: RuntimeTaskContext, turn: ActiveTurn, phase: ChatTurnPhase) {
        runCatching { publish(context, turn, phase) }
        log(turn.request, phase.name.lowercase(), turn.startedAt)
    }

    private class ActiveTurn(val request: ChatTurnRequest, val ids: ChatTurnIds, val startedAt: Instant) {
        var text = ""
        var firstChunk: Instant? = null
        var usage: ChatTurnUsage? = null
        var completed = false
    }

    private fun transcript(request: ChatTurnRequest, ids: ChatTurnIds, text: String, phase: ChatTurnPhase) = ChatTurnTranscript(
        request.taskId.value,
        request.generation,
        request.sessionId,
        ids.userId,
        ids.assistantId,
        ids.blockId,
        text,
        phase,
    )

    private fun log(request: ChatTurnRequest, outcome: String, startedAt: Instant) {
        val fields = JsonValue.Object.Builder().put("owner", "features/chat/backend")
            .put("stage", "chat.turn").put("outcome", outcome).put("database", "chat_v2")
            .put("duration_ms", Duration.between(startedAt, maxOf(startedAt, clock.now())).toMillis()).build()
        try {
            logger.log(
                LogRecord(
                    if (outcome == "failed") LogLevel.WARN else LogLevel.INFO,
                    "chat.turn.$outcome",
                    taskId = request.taskId,
                    fields = fields,
                ),
            )
        } catch (_: Exception) { }
    }

    private companion object {
        const val MAX_TEXT_LENGTH = 262_144
    }
}

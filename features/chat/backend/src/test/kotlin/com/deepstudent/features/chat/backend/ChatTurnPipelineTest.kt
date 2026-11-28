package com.deepstudent.features.chat.backend

import com.deepstudent.core.common.id.TaskId
import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.features.chat.api.ChatTurnContract
import com.deepstudent.features.chat.api.ChatTurnEvent
import com.deepstudent.features.chat.api.ChatTurnEventSink
import com.deepstudent.features.chat.api.ChatTurnInFlight
import com.deepstudent.features.chat.api.ChatTurnRecoveryPort
import com.deepstudent.features.chat.api.ChatTurnRequest
import com.deepstudent.features.chat.api.ChatTurnTransport
import com.deepstudent.features.chat.api.ChatTurnUsage
import com.deepstudent.features.chat.api.ChatTurnUsageMetadata
import com.deepstudent.features.chat.api.ChatTurnWritePort
import com.deepstudent.features.chat.backend.application.ChatTurnHandler
import com.deepstudent.features.chat.backend.application.ChatTurnPipeline
import com.deepstudent.features.chat.backend.application.ChatTurnRecoveryHandler
import com.deepstudent.features.chat.backend.application.ChatTurnTranscript
import com.deepstudent.services.runtime.api.RecoveryDecision
import com.deepstudent.services.runtime.api.RuntimeExecution
import com.deepstudent.services.runtime.api.RuntimeOwnership
import com.deepstudent.services.runtime.api.RuntimeSnapshot
import com.deepstudent.services.runtime.api.RuntimeState
import com.deepstudent.services.runtime.api.RuntimeTaskContext
import com.deepstudent.services.runtime.api.TaskCursor
import com.deepstudent.services.runtime.api.TaskSubmission
import com.deepstudent.services.runtime.api.TaskTimes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.Instant

class ChatTurnPipelineTest {
    private val taskId = TaskId.parse("task_s303")
    private val at = Instant.parse("2026-11-26T00:00:00Z")
    private fun request() = ChatTurnRequest(taskId, 1, ChatTurnContract.sessionId(taskId), "https://example.test/v1", "prompt")
    private fun payload() = JsonValue.obj(
        "endpoint" to JsonValue.text("https://example.test/v1"),
        "prompt" to JsonValue.text("prompt"),
        "model" to JsonValue.text("small"),
        "credentialReference" to JsonValue.text("secret-ref"),
    )

    @Test fun streamedTextIsPersistedBeforeCheckpointAndCompletedIsRequired() = runTest {
        val writes = RecordingWrites()
        val checkpoints = mutableListOf<JsonValue>()
        val context = Context(checkpoints)
        val transcript = mutableListOf<ChatTurnTranscript>()
        val pipeline = ChatTurnPipeline(
            transport = ChatTurnTransport { _, sink ->
                sink.emit(ChatTurnEvent.Text("中"))
                sink.emit(ChatTurnEvent.Text("文👩‍💻"))
                sink.emit(ChatTurnEvent.Completed)
            },
            writes = writes,
            observer = { transcript += it },
        )
        pipeline.execute(context, payload())
        assertEquals(listOf("begin", "append:中", "append:中文👩‍💻", "complete:中文👩‍💻"), writes.events)
        assertTrue(checkpoints.size >= 4)
        val firstAppendCheckpoint = checkpoints.first { it.obj("text") == "中" }
        assertEquals("running", firstAppendCheckpoint.obj("phase"))
        assertEquals("completed", checkpoints.last().obj("phase"))
        assertEquals("secret-ref" !in transcript.last().toString(), true)
        assertTrue(transcript.any { it.text == "中文👩‍💻" })
    }

    @Test fun transportWithoutCompletedIsFailedAndPartialRetained() = runTest {
        val writes = RecordingWrites()
        val pipeline = ChatTurnPipeline(ChatTurnTransport { _, sink -> sink.emit(ChatTurnEvent.Text("partial")) }, writes)
        val failure = runCatching { pipeline.execute(Context(), payload()) }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertEquals("append:partial", writes.events[1])
        assertEquals("fail:partial", writes.events[2])
        assertEquals(0, writes.events.count { it.startsWith("complete") })
    }

    @Test fun transportFailurePersistsFailedTerminalWithSafePartial() = runTest {
        val writes = RecordingWrites()
        val pipeline =
            ChatTurnPipeline(
                ChatTurnTransport { _, sink ->
                    sink.emit(ChatTurnEvent.Text("partial"))
                    throw IOException("secret")
                },
                writes,
            )
        assertTrue(runCatching { pipeline.execute(Context(), payload()) }.exceptionOrNull() is IOException)
        assertEquals("fail:partial", writes.events.last())
        assertTrue(writes.events.none { "secret" in it })
    }

    @Test fun cancellationPersistsCancelledFactAndPartialContent() = runTest {
        val writes = RecordingWrites()
        val pipeline =
            ChatTurnPipeline(
                ChatTurnTransport { _, sink ->
                    sink.emit(ChatTurnEvent.Text("partial"))
                    throw CancellationException("stop")
                },
                writes,
            )
        val failure = runCatching { pipeline.execute(Context(cancelRequested = true), payload()) }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertEquals("cancel:partial", writes.events.last())
        assertTrue(writes.events.none { "stop" in it })
    }

    @Test fun systemYieldDoesNotFabricateUserCancelledFact() = runTest {
        val writes = RecordingWrites()
        val pipeline =
            ChatTurnPipeline(
                ChatTurnTransport { _, sink ->
                    sink.emit(ChatTurnEvent.Text("partial"))
                    throw CancellationException("yield")
                },
                writes,
            )
        val failure = runCatching { pipeline.execute(Context(cancelRequested = false), payload()) }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertEquals(listOf("begin", "append:partial", "interrupt"), writes.events)
        assertTrue(writes.events.none { it.startsWith("cancel:") })
        assertTrue(writes.events.none { it.startsWith("fail:") })
    }

    @Test fun usageEventIsPassedToTerminalWriteOnly() = runTest {
        val writes = RecordingWrites()
        val usage = ChatTurnUsage(ChatTurnUsageMetadata("p", "m", "c", "a"), promptTokens = 1, completionTokens = 2, totalTokens = 3)
        val pipeline =
            ChatTurnPipeline(
                ChatTurnTransport { _, sink ->
                    sink.emit(ChatTurnEvent.Usage(usage))
                    sink.emit(ChatTurnEvent.Completed)
                },
                writes,
            )
        pipeline.execute(Context(), payload())
        assertEquals(usage, writes.usage)
    }

    @Test fun committedReplyDoesNotBecomeFailedWhenTerminalCheckpointFails() = runTest {
        val writes = RecordingWrites()
        val pipeline = ChatTurnPipeline(
            ChatTurnTransport { _, sink ->
                sink.emit(ChatTurnEvent.Text("saved"))
                sink.emit(ChatTurnEvent.Completed)
            },
            writes,
        )
        val context = Context(failTerminalCheckpoint = true)
        pipeline.execute(context, payload())
        assertEquals(listOf("begin", "append:saved", "complete:saved"), writes.events)
        assertTrue(context.terminalCheckpointAttempted)
    }

    @Test fun recoveryReadsGenerationAndReturnsInterruptedWithoutFabricatedFact() = runTest {
        val recovery = Recovery()
        val handler = ChatTurnRecoveryHandler(recovery)
        val decision = handler.decide(snapshot(RuntimeState.RUNNING))
        assertEquals(RecoveryDecision.Interrupted, decision)
        assertEquals(7L, recovery.generation)
        assertEquals("partial", handler.loadInFlight(taskId, 7)?.text)
    }

    private fun snapshot(state: RuntimeState, cancel: Boolean = false) = RuntimeSnapshot(
        taskId = taskId,
        kind = ChatTurnContract.KIND,
        state = state,
        submission = ChatTurnHandler.submission(request()),
        ownership = RuntimeOwnership(7, 2, "owner", at.plusSeconds(30)),
        execution = RuntimeExecution(null, cancel, null, null, null),
        times = TaskTimes(at, at),
        cursor = TaskCursor.ZERO,
    )

    private class Context(
        private val checkpoints: MutableList<JsonValue> = mutableListOf(),
        override val cancelRequested: Boolean = false,
        private val failTerminalCheckpoint: Boolean = false,
    ) : RuntimeTaskContext {
        override val taskId = TaskId.parse("task_s303")
        override val generation = 1L
        var terminalCheckpointAttempted = false
            private set

        override suspend fun checkpoint(value: JsonValue?) {
            if (value?.obj("phase") == "completed") {
                terminalCheckpointAttempted = true
                if (failTerminalCheckpoint) throw IOException("checkpoint unavailable")
            }
            checkpoints += requireNotNull(value)
        }
        override suspend fun transition(state: RuntimeState) = Unit
        override suspend fun renewLease() = Unit
    }

    private class RecordingWrites : ChatTurnWritePort {
        val events = mutableListOf<String>()
        var usage: ChatTurnUsage? = null
        override suspend fun begin(request: ChatTurnRequest, startedAt: Instant) {
            events += "begin"
        }
        override suspend fun append(request: ChatTurnRequest, text: String, firstChunkAt: Instant?, updatedAt: Instant) {
            events +=
                "append:$text"
        }
        override suspend fun complete(request: ChatTurnRequest, text: String, usage: ChatTurnUsage?, endedAt: Instant) {
            this.usage = usage
            events +=
                "complete:$text"
        }
        override suspend fun fail(request: ChatTurnRequest, text: String, usage: ChatTurnUsage?, endedAt: Instant) {
            events += "fail:$text"
        }
        override suspend fun interrupt(request: ChatTurnRequest, endedAt: Instant) {
            events += "interrupt"
        }
        override suspend fun cancel(request: ChatTurnRequest, text: String, usage: ChatTurnUsage?, endedAt: Instant) {
            events +=
                "cancel:$text"
        }
    }

    private class Recovery : ChatTurnRecoveryPort {
        var generation = 0L
        override suspend fun loadInFlight(taskId: TaskId, sessionId: String): ChatTurnInFlight = ChatTurnInFlight("partial", "running")
        override suspend fun loadInFlight(taskId: TaskId, generation: Long, sessionId: String): ChatTurnInFlight {
            this.generation = generation
            return ChatTurnInFlight("partial", "error")
        }
        override suspend fun settleInterrupted(taskId: TaskId, generation: Long, sessionId: String, at: Instant): RuntimeState {
            this.generation = generation
            return RuntimeState.INTERRUPTED
        }
    }
}

private fun JsonValue.obj(key: String): String = ((this as JsonValue.Object)[key] as? JsonValue.Text)?.value.orEmpty()

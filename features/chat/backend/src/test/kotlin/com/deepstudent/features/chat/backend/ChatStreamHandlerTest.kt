package com.deepstudent.features.chat.backend

import com.deepstudent.core.common.id.TaskId
import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.core.common.time.WallClock
import com.deepstudent.features.chat.api.ChatConversation
import com.deepstudent.features.chat.api.ChatRepository
import com.deepstudent.features.chat.api.ChatStreamTransport
import com.deepstudent.features.chat.backend.application.ChatStreamHandler
import com.deepstudent.services.runtime.api.RuntimeState
import com.deepstudent.services.runtime.api.RuntimeTaskContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.Instant

class ChatStreamHandlerTest {
    @Test fun partialTextIsCheckpointedAndFinalTextPreserved() = runTest {
        val context = Context()
        val repository = RecordingRepository()
        val clock = listOf(Instant.parse("2026-11-25T12:00:01Z"), Instant.parse("2026-11-25T12:00:00Z")).iterator()
        ChatStreamHandler(
            ChatStreamTransport { _, emit ->
                emit("中")
                assertEquals("中", (context.value?.get("text") as JsonValue.Text).value)
                assertTrue(repository.writes.isEmpty())
                emit("文👩‍💻")
            },
            repository,
            WallClock(clock::next),
        ).execute(context, payload())

        assertEquals("中文👩‍💻", (context.value?.get("text") as JsonValue.Text).value)
        assertEquals(3, context.writes)
        val chat = repository.writes.single()
        assertEquals("sess_chat_test", chat.session.id)
        assertEquals(chat.session.createdAt, chat.session.updatedAt)
        assertEquals(listOf("user", "assistant"), chat.messages.map { it.role })
        assertEquals(chat.messages.map { it.id }, chat.blocks.map { it.messageId })
        assertEquals(chat.messages.flatMap { it.blockIds }, chat.blocks.map { it.id })
        assertEquals(listOf("hello", "中文👩‍💻"), chat.blocks.map { it.details.content })
    }

    @Test fun cancellationIsNotConvertedIntoSuccess() = runTest {
        val context = Context()
        val repository = RecordingRepository()
        val handler = ChatStreamHandler(
            ChatStreamTransport { _, emit ->
                emit("partial")
                throw CancellationException()
            },
            repository,
        )
        assertTrue(runCatching { handler.execute(context, payload()) }.exceptionOrNull() is CancellationException)
        assertEquals(1, context.writes)
        assertTrue(repository.writes.isEmpty())
    }

    @Test fun oversizedDeltaIsRejectedBeforePersisting() = runTest {
        val context = Context()
        val repository = RecordingRepository()
        val handler = ChatStreamHandler(ChatStreamTransport { _, emit -> emit("x".repeat(262_145)) }, repository)
        assertTrue(runCatching { handler.execute(context, payload()) }.exceptionOrNull() is IllegalArgumentException)
        assertEquals(0, context.writes)
        assertTrue(repository.writes.isEmpty())
    }

    @Test fun repositoryFailureDoesNotBecomeCompletedTurn() = runTest {
        val context = Context()
        val repository = RecordingRepository(IOException("fixture write failure"))
        val handler = ChatStreamHandler(ChatStreamTransport { _, emit -> emit("answer") }, repository)
        assertTrue(runCatching { handler.execute(context, payload()) }.exceptionOrNull() is IOException)
        assertEquals("answer", (context.value?.get("text") as JsonValue.Text).value)
    }

    private fun payload() = JsonValue.Object.Builder().put("endpoint", "https://example.test/sse").put("prompt", "hello").build()

    private class RecordingRepository(private val failure: IOException? = null) : ChatRepository {
        val writes = mutableListOf<ChatConversation>()
        override suspend fun load(sessionId: String): ChatConversation? = null
        override suspend fun save(conversation: ChatConversation) {
            failure?.let { throw it }
            writes += conversation
        }
    }

    private class Context : RuntimeTaskContext {
        override val taskId = TaskId.parse("task_chat_test")
        override val generation = 1L
        var value: JsonValue.Object? = null
        var writes = 0
        override suspend fun checkpoint(value: JsonValue?) {
            this.value = value as JsonValue.Object
            writes++
        }
        override suspend fun transition(state: RuntimeState) = Unit
        override suspend fun renewLease() = Unit
    }
}

package com.deepstudent.features.chat.backend

import com.deepstudent.core.common.id.TaskId
import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.features.chat.api.ChatStreamTransport
import com.deepstudent.features.chat.backend.application.ChatStreamHandler
import com.deepstudent.services.runtime.api.RuntimeState
import com.deepstudent.services.runtime.api.RuntimeTaskContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ChatStreamHandlerTest {
    @Test fun partialTextIsCheckpointedAndFinalTextPreserved() = runTest {
        val context = Context()
        ChatStreamHandler(ChatStreamTransport { _, emit -> emit("中"); emit("文👩‍💻") }).execute(context, payload())
        assertEquals("中文👩‍💻", (context.value?.get("text") as JsonValue.Text).value)
        assertEquals(3, context.writes)
    }

    @Test fun cancellationIsNotConvertedIntoSuccess() = runTest {
        val context = Context()
        try {
            ChatStreamHandler(ChatStreamTransport { _, emit -> emit("partial"); throw CancellationException() })
                .execute(context, payload())
            error("cancellation was swallowed")
        } catch (_: CancellationException) {
            assertEquals(1, context.writes)
        }
    }

    @Test fun oversizedDeltaIsRejectedBeforePersisting() = runTest {
        val context = Context()
        try {
            ChatStreamHandler(ChatStreamTransport { _, emit -> emit("x".repeat(262_145)) }).execute(context, payload())
            error("budget ignored")
        } catch (_: IllegalArgumentException) { assertEquals(0, context.writes) }
    }

    private fun payload() = JsonValue.Object.Builder().put("endpoint", "https://example.test/sse").put("prompt", "hello").build()

    private class Context : RuntimeTaskContext {
        override val taskId = TaskId.parse("task_chat_test")
        override val generation = 1L
        var value: JsonValue.Object? = null
        var writes = 0
        override suspend fun checkpoint(value: JsonValue?) { this.value = value as JsonValue.Object; writes++ }
        override suspend fun transition(state: RuntimeState) = Unit
        override suspend fun renewLease() = Unit
    }
}

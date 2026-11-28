package com.deepstudent.app.runtime

import com.deepstudent.core.common.id.TaskId
import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.core.common.logging.NoopLogger
import com.deepstudent.features.chat.api.ChatStreamTransport
import com.deepstudent.features.chat.api.ChatTurnContract
import com.deepstudent.features.chat.api.ChatTurnEvent
import com.deepstudent.features.chat.api.ChatTurnRequest
import com.deepstudent.platform.network.OkHttpNetworkTransport
import com.deepstudent.services.providers.api.ProviderException
import com.deepstudent.services.providers.api.ProviderFailureCode
import com.deepstudent.services.providers.backend.ProviderConfigResolver
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class S304DirectChatRegressionTest {
    @Test
    fun preservedDirectTurnWireAndCompletion() = runBlocking {
        val reply = ProviderReplayReply.sse(listOf(FINISH, "[DONE]"))
        ProviderReplayPeer(reply).use { peer ->
            val events = mutableListOf<ChatTurnEvent>()
            val failure = directTurn(peer, events)
            assertNull(failure)
            assertEquals(listOf(ChatTurnEvent.Text("part"), ChatTurnEvent.Completed), events)
            val expected = """
                {"model":"direct-test","messages":[{"role":"user","content":"legacy prompt"}],
                 "stream":true,"stream_options":{"include_usage":true}}
            """.trimIndent()
            val captured = peer.captured()
            assertEquals(JsonValue.parseObject(expected), JsonValue.parseObject(captured.body))
            assertNull(captured.headers["authorization"])
        }
    }

    @Test
    fun directTurnDoesNotTreatMissingDoneAsCompletion() = runBlocking {
        ProviderReplayPeer(ProviderReplayReply.sse(listOf(FINISH))).use { peer ->
            val events = mutableListOf<ChatTurnEvent>()
            val failure = directTurn(peer, events) as ProviderException
            assertEquals(ProviderFailureCode.STREAM_TRUNCATED, failure.failure.code)
            assertEquals(listOf(ChatTurnEvent.Text("part")), events)
        }
    }

    private suspend fun directTurn(peer: ProviderReplayPeer, events: MutableList<ChatTurnEvent>): Throwable? = withTimeout(5_000) {
        ProviderReplayNetwork("$BASE/chat/completions", peer, OkHttpNetworkTransport()).use { network ->
            val task = TaskId.parse("task_s304_direct")
            val request = ChatTurnRequest(task, 1, ChatTurnContract.sessionId(task), BASE, "legacy prompt", model = "direct-test")
            val transport = MainChatStreamingTransport(
                legacy = ChatStreamTransport { _, _ -> error("not the raw SSE route") },
                providers = ProviderConfigResolver(),
                credentials = { error("no credential was configured") },
                adapters = mainProviderAdapters(network, NoopLogger, allowLoopback = false),
            )
            runCatching { transport.collect(request) { events += it } }.exceptionOrNull()
        }
    }

    private companion object {
        const val BASE = "https://legacy.example.test/v1"
        const val FINISH = """{"choices":[{"delta":{"content":"part"},"finish_reason":"stop"}]}"""
    }
}

package com.deepstudent.app.runtime

import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.core.common.logging.NoopLogger
import com.deepstudent.features.chat.api.ChatConversation
import com.deepstudent.features.chat.api.ChatRepository
import com.deepstudent.platform.network.OkHttpNetworkTransport
import com.deepstudent.services.providers.api.ProviderCallAccess
import com.deepstudent.services.providers.api.ProviderCredential
import com.deepstudent.services.providers.api.ProviderCredentialStore
import com.deepstudent.services.providers.api.ProviderEventSink
import com.deepstudent.services.providers.api.ProviderMessage
import com.deepstudent.services.providers.api.ProviderResult
import com.deepstudent.services.providers.api.ProviderRole
import com.deepstudent.services.providers.api.ProviderStreamEvent
import com.deepstudent.services.providers.backend.ProviderConfigResolver
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class S305ProviderConversationTest {
    @Test
    fun storesSuccessfulScopedResponsesAndUsesTheStoredHistoryOnTheNextCall() = runBlocking {
        val fixture = S305ReplayFixture.case("openai")
        val repository = ConversationMemory()
        val seen = mutableListOf<ProviderStreamEvent>()
        val completed = """{"type":"response.completed","response":{"status":"completed","output":[
            {"type":"reasoning","id":"rs_stored","encrypted_content":"synthetic-reply-state","summary":[]},
            {"type":"message","content":[{"type":"output_text","text":"first reply"}]}]}}""".replace("\n", "")
        ProviderReplayPeer(ProviderReplayReply.sse(listOf(completed), keepOpen = true)).use { peer ->
            val turn = MainProviderTurn(fixture.selection(), SESSION_ID, user("first prompt"))
            assertTrue(call(repository, fixture.endpoint, peer, turn, seen) is ProviderResult.Success)
            assertTrue(peer.disconnected())
        }
        assertFalse(seen.any { it is ProviderStreamEvent.ResponsesMetadata })
        assertTrue(seen.last() is ProviderStreamEvent.Completed)
        val assistant = checkNotNull(repository.load(SESSION_ID)).messages.last()
        val meta = assistant.payload.metadata as JsonValue.Object
        val item = meta.obj("responseReasoningItems")["__final_assistant__"]
        assertEquals(fixture.expectedHistoryScope, meta.text("stateScope"))
        ProviderReplayPeer(s305Ack()).use { peer ->
            val turn = MainProviderTurn(fixture.selection(), SESSION_ID, user("second prompt"))
            assertTrue(call(repository, fixture.endpoint, peer, turn) is ProviderResult.Success)
            val body = JsonValue.parseObject(peer.captured().body)
            assertTrue(body.array("input").contains(item))
        }
        assertEquals(4, repository.load(SESSION_ID)?.messages?.size)
    }

    private suspend fun call(
        repo: ChatRepository,
        endpoint: String,
        peer: ProviderReplayPeer,
        turn: MainProviderTurn,
        events: MutableList<ProviderStreamEvent> = mutableListOf(),
    ): ProviderResult<*> = ProviderReplayNetwork(endpoint, peer, OkHttpNetworkTransport()).use { network ->
        val access = ProviderCallAccess(
            ProviderCredentialStore { ProviderResult.Success(ProviderCredential.takeOwnership(S305ReplayFixture.token.toByteArray())) },
            mainProviderAdapters(network, NoopLogger, allowLoopback = false),
        )
        MainProviderConversations(repo, ProviderConfigResolver(), access).call(turn, events = ProviderEventSink { events += it })
    }

    private fun user(text: String) = ProviderMessage(ProviderRole.USER, text)

    private class ConversationMemory : ChatRepository {
        private var chat: ChatConversation? = null

        override suspend fun load(sessionId: String): ChatConversation? = chat?.takeIf { it.session.id == sessionId }

        override suspend fun save(conversation: ChatConversation) {
            val previous = chat
            chat = conversation.copy(
                messages = previous?.messages.orEmpty() + conversation.messages,
                blocks = previous?.blocks.orEmpty() + conversation.blocks,
            )
        }
    }

    private companion object {
        const val SESSION_ID = "sess_s305_conversation"
    }
}

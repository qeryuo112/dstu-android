package com.deepstudent.app.runtime

import com.deepstudent.features.chat.api.ChatConversation
import com.deepstudent.features.chat.api.ChatRepository
import com.deepstudent.services.providers.api.ProviderCallAccess
import com.deepstudent.services.providers.api.ProviderCancellation
import com.deepstudent.services.providers.api.ProviderCredentialStore
import com.deepstudent.services.providers.api.ProviderEventSink
import com.deepstudent.services.providers.api.ProviderFailureCode
import com.deepstudent.services.providers.api.ProviderMessage
import com.deepstudent.services.providers.api.ProviderResult
import com.deepstudent.services.providers.api.ProviderRole
import com.deepstudent.services.providers.api.ProviderStreamEvent
import com.deepstudent.services.providers.backend.ProviderConfigResolver
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class S305ProviderConversationFailureTest {
    @Test
    fun stopsBeforeHistoryOrCredentialsWhenAlreadyCancelled() = runBlocking {
        val target = S305ReplayFixture.case("openai").selection()
        val call = MainProviderConversations(UnexpectedRepository, ProviderConfigResolver(), NoAccess)
        val turn = MainProviderTurn(target, "sess_cancelled", ProviderMessage(ProviderRole.USER, "synthetic"))
        val result = call.call(turn, ProviderCancellation { true }, ProviderEventSink { error("unexpected cancelled event") })
        assertEquals(ProviderResult.Cancelled, result)
    }

    @Test
    fun invalidAuthEmitsNoCompletionAndDoesNotSave() = runBlocking {
        val target = S305ReplayFixture.case("openai").selection()
        val invalid = target.copy(vendor = target.vendor.copy(connection = target.vendor.connection.copy(credential = null)))
        val repository = object : ChatRepository {
            override suspend fun load(sessionId: String): ChatConversation? = null
            override suspend fun save(conversation: ChatConversation) = error("failed turn saved")
        }
        val call = MainProviderConversations(repository, ProviderConfigResolver(), NoAccess)
        val turn = MainProviderTurn(invalid, "sess_invalid", ProviderMessage(ProviderRole.USER, "synthetic"))
        val events = mutableListOf<ProviderStreamEvent>()
        val result = call.call(turn, events = ProviderEventSink { events += it })
        assertS305Failure(ProviderFailureCode.MISSING_CREDENTIAL_REFERENCE, result)
        assertTrue(events.isEmpty())
    }

    private object UnexpectedRepository : ChatRepository {
        override suspend fun load(sessionId: String): ChatConversation? = error("cancelled turn loaded history")
        override suspend fun save(conversation: ChatConversation) = error("cancelled turn saved")
    }

    private companion object {
        val NoAccess =
            ProviderCallAccess(ProviderCredentialStore { error("unexpected credential read") }) { error("unexpected adapter lookup") }
    }
}

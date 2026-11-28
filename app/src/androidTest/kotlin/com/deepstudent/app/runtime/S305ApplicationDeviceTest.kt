package com.deepstudent.app.runtime

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.deepstudent.app.DstuApplication
import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.features.chat.api.ChatConversation
import com.deepstudent.features.chat.api.ChatMessage
import com.deepstudent.services.providers.api.AuthMode
import com.deepstudent.services.providers.api.ModelProfile
import com.deepstudent.services.providers.api.ProviderMessage
import com.deepstudent.services.providers.api.ProviderProtocol
import com.deepstudent.services.providers.api.ProviderResult
import com.deepstudent.services.providers.api.ProviderRole
import com.deepstudent.services.providers.api.ProviderSelection
import com.deepstudent.services.providers.api.ProviderStopReason
import com.deepstudent.services.providers.api.ProviderStreamResult
import com.deepstudent.services.providers.api.VendorConfig
import com.deepstudent.services.providers.api.VendorConnection
import com.deepstudent.services.providers.api.VendorIdentity
import com.deepstudent.services.providers.api.VendorProtocolDefaults
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.util.UUID
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class S305ApplicationDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as DstuApplication

    @Test
    fun twoResponsesTurnsPersistReasoningAndReplayItThroughTheRealChatRepository() = runBlocking {
        assertEquals(ISOLATED_APPLICATION_ID, instrumentation.targetContext.packageName)
        val sessionId = "sess_s305_app_${UUID.randomUUID()}"
        val runtime = app.runtime()
        val repository = app.storage().chatRepository
        assertNull(repository.load(sessionId))

        MockWebServer().use { peer ->
            peer.start(InetAddress.getByName("127.0.0.1"), 0)
            peer.enqueue(sse(FIRST_FRAME))
            peer.enqueue(sse(SECOND_FRAME))
            val selection = loopbackSelection(peer)

            val first = call(runtime, selection, sessionId, "first prompt")
            assertEquals(ProviderStopReason.STOP, first.stopReason)
            assertEquals("first reply", first.text)
            val firstRequest = checkNotNull(peer.takeRequest(5, TimeUnit.SECONDS))
            assertEquals("POST", firstRequest.method)
            assertEquals("/v1/responses", firstRequest.path)
            assertNull(firstRequest.getHeader("Authorization"))
            val firstRaw = firstRequest.body.readUtf8()
            assertTrue(firstRaw, firstRaw.contains("first prompt"))
            assertEquals(JsonValue.text(MODEL), JsonValue.parseObject(firstRaw)["model"])

            val storedFirst = checkNotNull(repository.load(sessionId))
            assertEquals(2, storedFirst.messages.size)
            assertEquals(
                listOf("first prompt", "first reply"),
                storedFirst.messages.sortedBy { it.timestamp }.map { blockText(storedFirst, it) },
            )
            val persistedReasoning = reasoningItem(storedFirst, 0)
            assertEquals(REASONING_ID, (persistedReasoning["id"] as? JsonValue.Text)?.value)
            assertEquals("reasoning", (persistedReasoning["type"] as? JsonValue.Text)?.value)

            val second = call(runtime, selection, sessionId, "second prompt")
            assertEquals(ProviderStopReason.STOP, second.stopReason)
            assertEquals("second reply", second.text)
            val secondRequest = checkNotNull(peer.takeRequest(5, TimeUnit.SECONDS))
            assertEquals("POST", secondRequest.method)
            assertEquals("/v1/responses", secondRequest.path)
            assertNull(secondRequest.getHeader("Authorization"))
            val secondRaw = secondRequest.body.readUtf8()
            assertTrue(secondRaw, secondRaw.contains("second prompt"))
            val input = checkNotNull(JsonValue.parseObject(secondRaw)["input"] as? JsonValue.Array)
            val reasoningIndex = input.values.indexOf(persistedReasoning)
            val assistantIndex = input.values.indexOfFirst {
                (it as? JsonValue.Object)?.get("role") == JsonValue.text("assistant")
            }
            assertTrue("stored reasoning must precede its assistant item", reasoningIndex in 0 until assistantIndex)

            val stored = checkNotNull(repository.load(sessionId))
            assertEquals(sessionId, stored.session.id)
            assertEquals(4, stored.messages.size)
            assertEquals(4, stored.blocks.size)
            assertEquals(
                listOf("first prompt", "first reply", "second prompt", "second reply"),
                stored.messages.sortedBy { it.timestamp }.map { blockText(stored, it) },
            )
            val assistants = stored.messages.filter { it.role == "assistant" }.sortedBy { it.timestamp }
            assertEquals(REASONING_ID, (reasoningItem(stored, 0)["id"] as? JsonValue.Text)?.value)
            assertEquals(REASONING_ID_2, (reasoningItem(stored, 1)["id"] as? JsonValue.Text)?.value)
            assertEquals(2, assistants.size)
            assistants.forEach { message ->
                val metadata = checkNotNull(message.payload.metadata as? JsonValue.Object)
                assertNull(metadata["openai_responses_web_search_items"])
            }
        }
    }

    private suspend fun call(
        runtime: MainRuntimeResources,
        selection: ProviderSelection,
        sessionId: String,
        prompt: String,
    ): ProviderStreamResult {
        val result = runtime.providers.conversations.call(
            MainProviderTurn(selection, sessionId, ProviderMessage(ProviderRole.USER, prompt)),
        )
        return when (result) {
            is ProviderResult.Success -> result.value
            is ProviderResult.Failure -> throw AssertionError("provider failure: ${result.failure.code}")
            ProviderResult.Cancelled -> throw AssertionError("unexpected provider cancellation")
        }
    }

    private fun loopbackSelection(peer: MockWebServer): ProviderSelection {
        val vendorId = "s305-app-vendor"
        return ProviderSelection(
            vendor = VendorConfig(
                identity = VendorIdentity(vendorId, "openai", "openai"),
                connection = VendorConnection("http://127.0.0.1:${peer.port}/v1", AuthMode.NONE),
                protocolDefaults = VendorProtocolDefaults(
                    protocol = ProviderProtocol.OPENAI_RESPONSES,
                    supportsOpenaiResponses = true,
                ),
            ),
            profile = ModelProfile("s305-app-model", vendorId, MODEL),
            explicitProtocol = ProviderProtocol.OPENAI_RESPONSES,
        )
    }

    private fun blockText(conversation: ChatConversation, message: ChatMessage): String =
        conversation.blocks.single { it.id == message.blockIds.single() }.details.content.orEmpty()

    private fun reasoningItem(conversation: ChatConversation, assistantIndex: Int): JsonValue.Object {
        val assistant = conversation.messages.filter { it.role == "assistant" }.sortedBy { it.timestamp }[assistantIndex]
        val metadata = checkNotNull(assistant.payload.metadata as? JsonValue.Object)
        val items = checkNotNull(metadata["responseReasoningItems"] as? JsonValue.Object)
        return checkNotNull(items["__final_assistant__"] as? JsonValue.Object)
    }

    private fun sse(body: String) = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody(body)

    private companion object {
        const val ISOLATED_APPLICATION_ID = "com.deepstudent.app.s2device"
        const val MODEL = "test-model"
        const val REASONING_ID = "rs_s305_first"
        const val REASONING_ID_2 = "rs_s305_second"

        val FIRST_FRAME = "data: " + """{"type":"response.completed","response":{"status":"completed",""" +
            """"output":[{"type":"reasoning","id":"$REASONING_ID","encrypted_content":"s305-first-state",""" +
            """"summary":[]},{"type":"message","content":[{"type":"output_text","text":"first reply"}]}]}}""" + "\n\n"

        val SECOND_FRAME = "data: " + """{"type":"response.completed","response":{"status":"completed",""" +
            """"output":[{"type":"reasoning","id":"$REASONING_ID_2","encrypted_content":"s305-second-state",""" +
            """"summary":[]},{"type":"message","content":[{"type":"output_text","text":"second reply"}]}]}}""" + "\n\n"
    }
}

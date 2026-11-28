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
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.util.UUID
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class S304ApplicationDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as DstuApplication

    @Test
    fun usesRealApplicationChatCompletionsVendorRoute() = runBlocking {
        assertEquals(ISOLATED_APPLICATION_ID, instrumentation.targetContext.packageName)
        val sessionId = "sess_s304_app_${UUID.randomUUID()}"
        val runtime = app.runtime()
        val repository = app.storage().chatRepository
        assertNull(repository.load(sessionId))

        MockWebServer().use { peer ->
            peer.start(InetAddress.getByName("127.0.0.1"), 0)
            peer.enqueue(sse(FRAME))
            val selection = loopbackSelection(peer)

            val result = runtime.providers.conversations.call(
                MainProviderTurn(selection, sessionId, ProviderMessage(ProviderRole.USER, PROMPT)),
            )
            val reply = when (result) {
                is ProviderResult.Success -> result.value
                is ProviderResult.Failure -> throw AssertionError("provider failure: ${result.failure.code}")
                ProviderResult.Cancelled -> throw AssertionError("unexpected provider cancellation")
            }
            assertEquals(ProviderStopReason.STOP, reply.stopReason)
            assertEquals(REPLY, reply.text)

            val request = checkNotNull(peer.takeRequest(5, TimeUnit.SECONDS))
            assertEquals("POST", request.method)
            assertEquals("/v1/chat/completions", request.path)
            assertNull(request.getHeader("Authorization"))
            assertNull(request.getHeader("x-api-key"))
            val body = JsonValue.parseObject(request.body.readUtf8())
            assertEquals(JsonValue.text(MODEL), body["model"])
            val messages = checkNotNull(body["messages"] as? JsonValue.Array).values
            assertEquals(1, messages.size)
            val user = messages.single() as JsonValue.Object
            assertEquals(JsonValue.text("user"), user["role"])
            assertEquals(JsonValue.text(PROMPT), user["content"])

            val stored = checkNotNull(repository.load(sessionId))
            assertEquals(2, stored.messages.size)
            assertEquals(
                listOf(PROMPT, REPLY),
                stored.messages.sortedBy { it.timestamp }.map { blockText(stored, it) },
            )
            assertEquals(1, stored.messages.count { it.role == "assistant" })
            assertEquals(1, stored.blocks.count { it.details.content == REPLY })
        }
    }

    private fun loopbackSelection(peer: MockWebServer): ProviderSelection {
        val vendorId = "s304-app-vendor"
        return ProviderSelection(
            vendor = VendorConfig(
                identity = VendorIdentity(vendorId, "siliconflow", "siliconflow"),
                connection = VendorConnection("http://127.0.0.1:${peer.port}/v1", AuthMode.NONE),
                protocolDefaults = VendorProtocolDefaults(protocol = ProviderProtocol.OPENAI_CHAT_COMPLETIONS),
            ),
            profile = ModelProfile("s304-app-model", vendorId, MODEL),
            explicitProtocol = ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
        )
    }

    private fun blockText(conversation: ChatConversation, message: ChatMessage): String =
        conversation.blocks.single { it.id == message.blockIds.single() }.details.content.orEmpty()

    private fun sse(frame: String) = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody(frame)

    private companion object {
        const val ISOLATED_APPLICATION_ID = "com.deepstudent.app.s2device"
        const val MODEL = "Qwen/Qwen2.5-7B-Instruct"
        const val PROMPT = "route this prompt through the real application stack"
        const val REPLY = "vendor route acknowledged"

        val FRAME = sseBody(
            """{"id":"chatcmpl-s304","object":"chat.completion.chunk","choices":[{"index":0,""" +
                """"delta":{"role":"assistant","content":"$REPLY"},"finish_reason":null}]}""",
            """{"id":"chatcmpl-s304","object":"chat.completion.chunk","choices":[{"index":0,""" +
                """"delta":{},"finish_reason":"stop"}]}""",
            "[DONE]",
        )

        fun sseBody(vararg frames: String): String = frames.joinToString("") { "data: $it\n\n" }
    }
}

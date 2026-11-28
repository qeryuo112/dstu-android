package com.deepstudent.app.runtime

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.deepstudent.app.DstuApplication
import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.platform.security.CredentialKey
import com.deepstudent.platform.security.SecretBytes
import com.deepstudent.services.providers.api.AuthMode
import com.deepstudent.services.providers.api.CredentialReference
import com.deepstudent.services.providers.api.ModelProfile
import com.deepstudent.services.providers.api.ProviderMessage
import com.deepstudent.services.providers.api.ProviderNativeMessage
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.util.UUID
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class S306ApplicationDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as DstuApplication

    @Test
    fun scopedThinkingAndImagesReplayOnlyToTheSameTarget() = runBlocking {
        assertEquals(ISOLATED_APPLICATION_ID, instrumentation.targetContext.packageName)
        val runtime = app.runtime()
        val repository = app.storage().chatRepository
        val sessionId = "sess_s306_app_${UUID.randomUUID()}"
        assertNull(repository.load(sessionId))

        val security = app.security()
        val credentialKey = CredentialKey.of(CREDENTIAL_REFERENCE)
        val secret = SecretBytes.fromBytes(SYNTHETIC_API_KEY.encodeToByteArray())
        try {
            security.credentials.put(credentialKey, secret)
            val stored = security.credentials.get(credentialKey)
            try {
                stored.use { bytes -> assertEquals(SYNTHETIC_API_KEY, bytes.decodeToString()) }
            } finally {
                stored.close()
            }

            MockWebServer().use { peer ->
                peer.start(InetAddress.getByName("127.0.0.1"), 0)
                peer.enqueue(sse(FIRST_FRAME))
                peer.enqueue(sse(SECOND_FRAME))
                peer.enqueue(sse(THIRD_FRAME))
                val sameTarget = loopbackSelection(peer, MODEL)

                // Round one: signed thinking + redacted state + a synthetic image, persisted as provider state.
                val firstTurn = MainProviderTurn(
                    sameTarget,
                    sessionId,
                    ProviderMessage(
                        ProviderRole.USER,
                        "first prompt",
                        nativeContent = ProviderNativeMessage(imageDataUrls = listOf(IMAGE_DATA_URL)),
                    ),
                )
                val first = call(runtime, firstTurn)
                assertEquals(ProviderStopReason.STOP, first.stopReason)
                assertEquals("first reply", first.text)

                val firstRequest = checkNotNull(peer.takeRequest(5, TimeUnit.SECONDS))
                assertEquals("POST", firstRequest.method)
                assertEquals("/v1/messages", firstRequest.path)
                assertEquals(SYNTHETIC_API_KEY, firstRequest.getHeader("x-api-key"))
                assertEquals(ANTHROPIC_VERSION, firstRequest.getHeader("anthropic-version"))
                assertNull(firstRequest.getHeader("Authorization"))
                val firstRaw = firstRequest.body.readUtf8()
                val firstBody = JsonValue.parseObject(firstRaw)
                assertEquals(JsonValue.text(MODEL), firstBody["model"])
                val firstUser = wireMessages(firstBody).single { wireRole(it) == "user" }
                val firstParts = wireBlocks(firstUser)
                val image = firstParts.single { wireType(it) == "image" }
                val source = checkNotNull(image["source"] as? JsonValue.Object)
                assertEquals("base64", (source["type"] as? JsonValue.Text)?.value)
                assertEquals("image/png", (source["media_type"] as? JsonValue.Text)?.value)
                assertEquals("AQID", (source["data"] as? JsonValue.Text)?.value)
                assertTrue(firstParts.any { wireType(it) == "text" && wireField(it, "text") == "first prompt" })

                val storedFirst = checkNotNull(repository.load(sessionId))
                assertEquals(2, storedFirst.messages.size)
                val firstAssistant = storedFirst.messages.single { it.role == "assistant" }
                val metadata = checkNotNull(firstAssistant.payload.metadata as? JsonValue.Object)
                val providerState = checkNotNull(metadata["providerState"] as? JsonValue.Object)
                val scope = checkNotNull((providerState["stateScope"] as? JsonValue.Text)?.value)
                val tuple = checkNotNull(JsonValue.parse(scope) as? JsonValue.Array).values
                assertEquals(4, tuple.size)
                assertEquals("anthropic", (tuple[0] as? JsonValue.Text)?.value)
                assertTrue((tuple[1] as? JsonValue.Text)?.value.orEmpty().matches(Regex("[0-9a-f]{8}")))
                assertEquals(MODEL, (tuple[2] as? JsonValue.Text)?.value)
                assertEquals("anthropic_messages", (tuple[3] as? JsonValue.Text)?.value)
                val items = checkNotNull(providerState["items"] as? JsonValue.Array).values
                    .map { it as JsonValue.Object }
                assertEquals(2, items.size)
                assertEquals("thinking", wireType(items[0]))
                assertEquals(THINKING_TEXT, wireField(items[0], "thinking"))
                assertEquals(SIGNATURE, wireField(items[0], "signature"))
                assertEquals("redacted_thinking", wireType(items[1]))
                assertEquals(REDACTED_DATA, wireField(items[1], "data"))

                // Round two: the same model replays the signature and the redacted block verbatim.
                val second = call(runtime, MainProviderTurn(sameTarget, sessionId, ProviderMessage(ProviderRole.USER, "second prompt")))
                assertEquals(ProviderStopReason.STOP, second.stopReason)
                assertEquals("second reply", second.text)

                val secondRequest = checkNotNull(peer.takeRequest(5, TimeUnit.SECONDS))
                assertEquals("POST", secondRequest.method)
                assertEquals("/v1/messages", secondRequest.path)
                assertEquals(SYNTHETIC_API_KEY, secondRequest.getHeader("x-api-key"))
                val secondRaw = secondRequest.body.readUtf8()
                val secondBody = JsonValue.parseObject(secondRaw)
                assertEquals(JsonValue.text(MODEL), secondBody["model"])
                val secondAssistant = wireMessages(secondBody).single { wireRole(it) == "assistant" }
                val replay = wireBlocks(secondAssistant)
                assertEquals(3, replay.size)
                assertEquals("thinking", wireType(replay[0]))
                assertEquals(THINKING_TEXT, wireField(replay[0], "thinking"))
                assertEquals(SIGNATURE, wireField(replay[0], "signature"))
                assertEquals("redacted_thinking", wireType(replay[1]))
                assertEquals(REDACTED_DATA, wireField(replay[1], "data"))
                assertEquals("text", wireType(replay[2]))
                assertEquals("first reply", wireField(replay[2], "text"))
                assertEquals(4, checkNotNull(repository.load(sessionId)).messages.size)

                // Round three: a different Claude target keeps the visible thinking text but drops signature and redacted state.
                val otherTarget = loopbackSelection(peer, OTHER_MODEL)
                val third = call(runtime, MainProviderTurn(otherTarget, sessionId, ProviderMessage(ProviderRole.USER, "third prompt")))
                assertEquals(ProviderStopReason.STOP, third.stopReason)
                assertEquals("third reply", third.text)

                val thirdRequest = checkNotNull(peer.takeRequest(5, TimeUnit.SECONDS))
                assertEquals("POST", thirdRequest.method)
                assertEquals("/v1/messages", thirdRequest.path)
                assertEquals(SYNTHETIC_API_KEY, thirdRequest.getHeader("x-api-key"))
                val thirdRaw = thirdRequest.body.readUtf8()
                val thirdBody = JsonValue.parseObject(thirdRaw)
                assertEquals(JsonValue.text(OTHER_MODEL), thirdBody["model"])
                val sanitized = wireMessages(thirdBody)
                    .filter { wireRole(it) == "assistant" }
                    .single { message -> wireBlocks(message).any { wireType(it) == "text" && wireField(it, "text") == THINKING_TEXT } }
                val sanitizedBlocks = wireBlocks(sanitized)
                assertTrue(sanitizedBlocks.any { wireType(it) == "text" && wireField(it, "text") == THINKING_TEXT })
                assertTrue(sanitizedBlocks.any { wireType(it) == "text" && wireField(it, "text") == "first reply" })
                assertFalse(sanitizedBlocks.any { wireType(it) == "thinking" })
                assertFalse(sanitizedBlocks.any { wireType(it) == "redacted_thinking" })
                assertFalse("signature must not cross targets", thirdRaw.contains("\"signature\""))
                assertFalse("redacted state must not cross targets", thirdRaw.contains(REDACTED_DATA))
                assertEquals(6, checkNotNull(repository.load(sessionId)).messages.size)
            }
        } finally {
            secret.close()
            security.credentials.delete(credentialKey)
        }
    }

    private suspend fun call(runtime: MainRuntimeResources, turn: MainProviderTurn): ProviderStreamResult {
        val result = runtime.providers.conversations.call(turn)
        return when (result) {
            is ProviderResult.Success -> result.value
            is ProviderResult.Failure -> throw AssertionError("provider failure: ${result.failure.code}")
            ProviderResult.Cancelled -> throw AssertionError("unexpected provider cancellation")
        }
    }

    private fun loopbackSelection(peer: MockWebServer, model: String): ProviderSelection {
        val vendorId = "s306-app-vendor"
        return ProviderSelection(
            vendor = VendorConfig(
                identity = VendorIdentity(vendorId, "anthropic", "anthropic"),
                connection = VendorConnection(
                    "http://127.0.0.1:${peer.port}/v1",
                    AuthMode.X_API_KEY,
                    CredentialReference(CREDENTIAL_REFERENCE),
                ),
                protocolDefaults = VendorProtocolDefaults(protocol = ProviderProtocol.ANTHROPIC_MESSAGES),
            ),
            profile = ModelProfile("s306-app-model", vendorId, model),
            explicitProtocol = ProviderProtocol.ANTHROPIC_MESSAGES,
        )
    }

    private fun wireMessages(body: JsonValue.Object): List<JsonValue.Object> =
        checkNotNull(body["messages"] as? JsonValue.Array).values.map { it as JsonValue.Object }

    private fun wireRole(message: JsonValue.Object): String? = (message["role"] as? JsonValue.Text)?.value

    private fun wireBlocks(message: JsonValue.Object): List<JsonValue.Object> =
        (message["content"] as? JsonValue.Array)?.values.orEmpty().map { it as JsonValue.Object }

    private fun wireType(block: JsonValue.Object): String? = (block["type"] as? JsonValue.Text)?.value

    private fun wireField(block: JsonValue.Object, name: String): String? = (block[name] as? JsonValue.Text)?.value

    private fun sse(frame: String) = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody(frame)

    private companion object {
        const val ISOLATED_APPLICATION_ID = "com.deepstudent.app.s2device"
        const val CREDENTIAL_REFERENCE = "provider.chat.s306-app"
        const val SYNTHETIC_API_KEY = "sk-s306-app-synthetic-key"
        const val ANTHROPIC_VERSION = "2023-06-01"
        const val MODEL = "claude-sonnet-4-5"
        const val OTHER_MODEL = "claude-opus-4-1"
        const val IMAGE_DATA_URL = "data:image/png;base64,AQID"
        const val THINKING_TEXT = "需要先核对目标再决定是否回放签名"
        const val SIGNATURE = "EqQBCgS306AppSignature"
        const val REDACTED_DATA = "opaque_s306_redacted_payload"

        val FIRST_FRAME = sseBody(
            """{"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":""}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"$THINKING_TEXT"}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"$SIGNATURE"}}""",
            """{"type":"content_block_stop","index":0}""",
            """{"type":"content_block_start","index":1,"content_block":{"type":"text","text":""}}""",
            """{"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"first reply"}}""",
            """{"type":"content_block_stop","index":1}""",
            """{"type":"content_block_start","index":2,"content_block":{"type":"redacted_thinking","data":"$REDACTED_DATA"}}""",
            """{"type":"content_block_stop","index":2}""",
            """{"type":"message_delta","delta":{"stop_reason":"end_turn"}}""",
            """{"type":"message_stop"}""",
        )

        val SECOND_FRAME = sseBody(
            """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"second reply"}}""",
            """{"type":"content_block_stop","index":0}""",
            """{"type":"message_delta","delta":{"stop_reason":"end_turn"}}""",
            """{"type":"message_stop"}""",
        )

        val THIRD_FRAME = sseBody(
            """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"third reply"}}""",
            """{"type":"content_block_stop","index":0}""",
            """{"type":"message_delta","delta":{"stop_reason":"end_turn"}}""",
            """{"type":"message_stop"}""",
        )

        fun sseBody(vararg frames: String): String = frames.joinToString("") { "data: $it\n\n" }
    }
}

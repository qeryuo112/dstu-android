package com.deepstudent.app.runtime

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.deepstudent.app.DstuApplication
import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.services.providers.api.AuthMode
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
class S307ApplicationDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as DstuApplication

    @Test
    fun storesGeminiSignatureAndReplaysPreparedImagesThroughTheApplication() = runBlocking {
        assertEquals(ISOLATED_APPLICATION_ID, instrumentation.targetContext.packageName)
        val runtime = app.runtime()
        val repository = app.storage().chatRepository

        // Both official Google entries route through the same google adapter; neither needs a key.
        for (provider in listOf(PROVIDER_GOOGLE, PROVIDER_GEMINI)) {
            val sessionId = "sess_s307_app_${provider}_${UUID.randomUUID()}"
            assertNull(repository.load(sessionId))

            MockWebServer().use { peer ->
                peer.start(InetAddress.getByName("127.0.0.1"), 0)
                peer.enqueue(sse(FIRST_FRAME))
                peer.enqueue(sse(SECOND_FRAME))

                // Round one: a prepared image data URL plus a signed Gemini 3 reply.
                val first = call(
                    runtime,
                    MainProviderTurn(
                        loopbackSelection(peer, provider, MODEL),
                        sessionId,
                        ProviderMessage(
                            ProviderRole.USER,
                            FIRST_PROMPT,
                            nativeContent = ProviderNativeMessage(imageDataUrls = listOf(IMAGE_DATA_URL)),
                        ),
                    ),
                )
                assertEquals(ProviderStopReason.STOP, first.stopReason)
                assertEquals(REPLY, first.text)

                val firstRequest = checkNotNull(peer.takeRequest(5, TimeUnit.SECONDS))
                assertEquals("POST", firstRequest.method)
                val firstPath = checkNotNull(firstRequest.path)
                assertTrue(firstPath.contains("/models/$MODEL:streamGenerateContent"))
                assertTrue(firstPath.endsWith(":streamGenerateContent?alt=sse"))
                // AuthMode.NONE: the key query and the x-goog-api-key header stay absent.
                assertFalse(firstPath.contains("key="))
                assertNull(firstRequest.getHeader("x-goog-api-key"))
                assertNull(firstRequest.getHeader("Authorization"))

                val firstBody = JsonValue.parseObject(firstRequest.body.readUtf8())
                val firstUserParts = wireParts(wireContents(firstBody).single { wireRole(it) == "user" })
                assertTrue(firstUserParts.any { wireField(it, "text") == FIRST_PROMPT })
                val inlineImage = checkNotNull(firstUserParts.single { it["inlineData"] != null }["inlineData"] as? JsonValue.Object)
                assertEquals("image/png", wireField(inlineImage, "mimeType"))
                assertEquals("AQID", wireField(inlineImage, "data"))

                // The signature is private SQLite provider state bound to the exact target tuple.
                val storedFirst = checkNotNull(repository.load(sessionId))
                assertEquals(2, storedFirst.messages.size)
                val assistant = storedFirst.messages.single { it.role == "assistant" }
                val metadata = checkNotNull(assistant.payload.metadata as? JsonValue.Object)
                val providerState = checkNotNull(metadata["providerState"] as? JsonValue.Object)
                val scope = checkNotNull((providerState["stateScope"] as? JsonValue.Text)?.value)
                val tuple = checkNotNull(JsonValue.parse(scope) as? JsonValue.Array).values
                assertEquals(4, tuple.size)
                assertEquals(provider, (tuple[0] as? JsonValue.Text)?.value)
                assertTrue((tuple[1] as? JsonValue.Text)?.value.orEmpty().matches(Regex("[0-9a-f]{8}")))
                assertEquals(MODEL, (tuple[2] as? JsonValue.Text)?.value)
                assertEquals(GOOGLE_PROTOCOL, (tuple[3] as? JsonValue.Text)?.value)
                val items = checkNotNull(providerState["items"] as? JsonValue.Object)
                assertEquals(SIGNATURE, wireField(items, "thoughtSignature"))

                // Round two: a different model is a different target. The stored image is replayed,
                // while the scoped signature never degrades into an ordinary prompt text.
                val second = call(
                    runtime,
                    MainProviderTurn(
                        loopbackSelection(peer, provider, OTHER_MODEL),
                        sessionId,
                        ProviderMessage(ProviderRole.USER, SECOND_PROMPT),
                    ),
                )
                assertEquals(ProviderStopReason.STOP, second.stopReason)
                assertEquals(SECOND_REPLY, second.text)

                val secondRequest = checkNotNull(peer.takeRequest(5, TimeUnit.SECONDS))
                assertEquals("POST", secondRequest.method)
                val secondPath = checkNotNull(secondRequest.path)
                assertTrue(secondPath.contains("/models/$OTHER_MODEL:streamGenerateContent"))
                assertFalse(secondPath.contains("key="))
                assertNull(secondRequest.getHeader("x-goog-api-key"))

                val secondRaw = secondRequest.body.readUtf8()
                val secondBody = JsonValue.parseObject(secondRaw)
                val secondParts = wireContents(secondBody).flatMap(::wireParts)
                assertFalse("signature leaked across targets", secondRaw.contains(SIGNATURE))
                assertFalse("signature field crossed targets", secondRaw.contains("thought_signature"))
                assertFalse("signature part crossed targets", secondRaw.contains("thoughtSignature"))
                val secondTexts = secondParts.mapNotNull { wireField(it, "text") }
                assertTrue(secondTexts.contains(SECOND_PROMPT))
                assertTrue(secondTexts.contains(REPLY))
                assertTrue("signature must not become prompt text", secondTexts.none { it.contains(SIGNATURE) })
                val replayedImage = checkNotNull(secondParts.single { it["inlineData"] != null }["inlineData"] as? JsonValue.Object)
                assertEquals("image/png", wireField(replayedImage, "mimeType"))
                assertEquals("AQID", wireField(replayedImage, "data"))

                assertEquals(4, checkNotNull(repository.load(sessionId)).messages.size)
            }
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

    private fun loopbackSelection(peer: MockWebServer, provider: String, model: String): ProviderSelection {
        val vendorId = "s307-app-vendor-$provider"
        return ProviderSelection(
            vendor = VendorConfig(
                identity = VendorIdentity(vendorId, provider, provider),
                connection = VendorConnection("http://127.0.0.1:${peer.port}/v1", AuthMode.NONE),
                protocolDefaults = VendorProtocolDefaults(protocol = ProviderProtocol.GOOGLE_GENERATE_CONTENT),
            ),
            profile = ModelProfile("s307-app-model-$provider", vendorId, model),
            explicitProtocol = ProviderProtocol.GOOGLE_GENERATE_CONTENT,
        )
    }

    private fun wireContents(body: JsonValue.Object): List<JsonValue.Object> =
        checkNotNull(body["contents"] as? JsonValue.Array).values.map { it as JsonValue.Object }

    private fun wireRole(content: JsonValue.Object): String? = (content["role"] as? JsonValue.Text)?.value

    private fun wireParts(content: JsonValue.Object): List<JsonValue.Object> =
        (content["parts"] as? JsonValue.Array)?.values.orEmpty().map { it as JsonValue.Object }

    private fun wireField(value: JsonValue.Object, name: String): String? = (value[name] as? JsonValue.Text)?.value

    private fun sse(payload: String) = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody("data: $payload\n\n")

    private companion object {
        const val ISOLATED_APPLICATION_ID = "com.deepstudent.app.s2device"
        const val PROVIDER_GOOGLE = "google"
        const val PROVIDER_GEMINI = "gemini"
        const val GOOGLE_PROTOCOL = "google_generate_content"
        const val MODEL = "gemini-3-pro-preview"
        const val OTHER_MODEL = "gemini-2.5-flash"
        const val IMAGE_DATA_URL = "data:image/png;base64,AQID"
        const val FIRST_PROMPT = "first prompt"
        const val SECOND_PROMPT = "second prompt"
        const val REPLY = "reply"
        const val SECOND_REPLY = "second reply"
        const val SIGNATURE = "synthetic-gemini-signature"

        const val FIRST_FRAME =
            """{"candidates":[{"content":{"parts":[{"text":"$REPLY","thoughtSignature":"$SIGNATURE"}]},"finishReason":"STOP"}]}"""

        const val SECOND_FRAME =
            """{"candidates":[{"content":{"parts":[{"text":"$SECOND_REPLY"}]},"finishReason":"STOP"}]}"""
    }
}

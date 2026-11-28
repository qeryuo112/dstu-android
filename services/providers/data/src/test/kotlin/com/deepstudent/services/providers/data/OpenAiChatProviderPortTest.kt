package com.deepstudent.services.providers.data

import com.deepstudent.platform.network.HttpLimits
import com.deepstudent.platform.network.NetworkRequest
import com.deepstudent.platform.network.NetworkTransport
import com.deepstudent.platform.network.ResponseHead
import com.deepstudent.platform.network.SseEnd
import com.deepstudent.platform.network.SseEvent
import com.deepstudent.platform.network.SseLimits
import com.deepstudent.services.providers.api.AuthMode
import com.deepstudent.services.providers.api.ModelProfile
import com.deepstudent.services.providers.api.ModelRuntime
import com.deepstudent.services.providers.api.ProviderAdapterRequest
import com.deepstudent.services.providers.api.ProviderCancellation
import com.deepstudent.services.providers.api.ProviderCredential
import com.deepstudent.services.providers.api.ProviderException
import com.deepstudent.services.providers.api.ProviderFailureCode
import com.deepstudent.services.providers.api.ProviderMessage
import com.deepstudent.services.providers.api.ProviderProtocol
import com.deepstudent.services.providers.api.ProviderProtocolSource
import com.deepstudent.services.providers.api.ProviderRole
import com.deepstudent.services.providers.api.ProviderRoute
import com.deepstudent.services.providers.api.ProviderStopReason
import com.deepstudent.services.providers.api.ProviderStreamEvent
import com.deepstudent.services.providers.api.ProviderStreamResult
import com.deepstudent.services.providers.api.ProviderUsage
import com.deepstudent.services.providers.api.ResolvedProviderConfig
import com.deepstudent.services.providers.api.VendorConfig
import com.deepstudent.services.providers.api.VendorConnection
import com.deepstudent.services.providers.api.VendorIdentity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiChatProviderPortTest {
    @Test
    fun `port streams text usage and completion with raw credential`() = runBlocking {
        val network = RecordingNetwork(
            events = listOf(
                """{"choices":[{"delta":{"content":"hello"},"finish_reason":null}]}""",
                """{"choices":[{"delta":{"content":" world"},"finish_reason":"stop"}]}""",
                """{"choices":[],"usage":{"prompt_tokens":4,"completion_tokens":3,"total_tokens":7}}""",
                "[DONE]",
            ),
        )
        val emitted = mutableListOf<ProviderStreamEvent>()
        val credential = ProviderCredential.fromBytes("secret".toByteArray())
        val port = OpenAiChatProviderFactory(network).create(resolved(authMode = AuthMode.BEARER_API_KEY), credential)
        val result = port.stream(request(authMode = AuthMode.BEARER_API_KEY), ProviderCancellation { false }) { emitted += it }
        assertEquals(
            ProviderStreamResult(
                "hello world",
                ProviderUsage(inputTokens = 4, outputTokens = 3, totalTokens = 7),
                ProviderStopReason.STOP,
            ),
            result,
        )
        assertEquals(listOf("hello", " world"), emitted.filterIsInstance<ProviderStreamEvent.TextDelta>().map { it.text })
        assertTrue(emitted.last() is ProviderStreamEvent.Completed)
        assertEquals("Bearer secret", network.request?.headers?.firstOrNull { it.name == "Authorization" }?.value)
        val body = network.request?.body?.decodeToString() ?: error("missing request")
        assertTrue(body.contains("\"stream\":true"))
        assertTrue(body.contains("\"max_tokens\":8192"))
    }

    @Test
    fun `port maps http error and truncation without retry`() = runBlocking {
        val http = RecordingNetwork(end = SseEnd.REJECTED_HTTP_STATUS, status = 429)
        runCatching {
            OpenAiChatProviderFactory(http).create(resolved(), null)!!.stream(request(), ProviderCancellation { false }) {}
        }.onSuccess { error("accepted HTTP failure") }.onFailure {
            assertEquals(ProviderFailureCode.RATE_LIMITED, (it as ProviderException).failure.code)
        }
        assertEquals(1, http.calls)

        val truncated = RecordingNetwork(events = listOf("""{"choices":[{"delta":{"content":"part"}}]}"""))
        runCatching {
            OpenAiChatProviderFactory(truncated).create(resolved(), null)!!.stream(request(), ProviderCancellation { false }) {}
        }.onSuccess { error("accepted truncated stream") }.onFailure {
            assertEquals(ProviderFailureCode.STREAM_TRUNCATED, (it as ProviderException).failure.code)
        }
        assertEquals(1, truncated.calls)
    }

    @Test
    fun `port forwards reasoning tool deltas and provider error without completion`(): Unit = runBlocking {
        val network = RecordingNetwork(
            events = listOf(
                """{"choices":[{"delta":{"reasoning":"think","tool_calls":[{"index":0,"id":"call_1",""" +
                    """"function":{"name":"lookup","arguments":"{"}}]}}]}""",
                """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"}"}}],"finish_reason":"tool_calls"}}]}""",
                "[DONE]",
            ),
        )
        val emitted = mutableListOf<ProviderStreamEvent>()
        val result = OpenAiChatProviderFactory(network, key = key("custom"))
            .create(resolved(), null)
            .stream(request(), ProviderCancellation { false }) { emitted += it }
        assertEquals(ProviderStopReason.TOOL_CALL, result.stopReason)
        assertTrue(emitted.any { it is ProviderStreamEvent.ReasoningDelta && it.text == "think" })
        assertEquals(2, emitted.filterIsInstance<ProviderStreamEvent.ToolCallDelta>().size)
        assertTrue(emitted.last() is ProviderStreamEvent.Completed)

        val providerError = RecordingNetwork(events = listOf("""{"error":{"code":"bad","message":"rejected"}}""", "[DONE]"))
        runCatching {
            OpenAiChatProviderFactory(providerError, key = key("custom"))
                .create(resolved(), null)
                .stream(request(), ProviderCancellation { false }) {}
        }.onSuccess { error("accepted provider error") }.onFailure {
            assertEquals(ProviderFailureCode.PROVIDER_ERROR, (it as ProviderException).failure.code)
        }
    }

    @Test
    fun `port preserves exact text and empty reasoning fragments`(): Unit = runBlocking {
        val network = RecordingNetwork(
            events = listOf(
                """{"choices":[{"delta":{"content":"A","reasoning_content":""},"finish_reason":null}]}""",
                """{"choices":[{"delta":{"content":"A","reasoning_content":"tail"},"finish_reason":"stop"}]}""",
                "[DONE]",
            ),
        )
        val emitted = mutableListOf<ProviderStreamEvent>()
        val result = OpenAiChatProviderFactory(network, key = key("custom"))
            .create(resolved(), null)
            .stream(request(), ProviderCancellation { false }) { emitted += it }
        assertEquals("AA", result.text)
        assertEquals(listOf("A", "A"), emitted.filterIsInstance<ProviderStreamEvent.TextDelta>().map { it.text })
        assertEquals(listOf("", "tail"), emitted.filterIsInstance<ProviderStreamEvent.ReasoningDelta>().map { it.text })
        assertEquals(1, emitted.count { it is ProviderStreamEvent.Completed })
    }

    @Test
    fun `port checks cancellation before and after transport`(): Unit = runBlocking {
        val network = RecordingNetwork(events = listOf("""{"choices":[{"delta":{"content":"x"},"finish_reason":"stop"}]}""", "[DONE]"))
        runCatching {
            OpenAiChatProviderFactory(network, key = key("custom"))
                .create(resolved(), null)
                .stream(request(), ProviderCancellation { true }) {}
        }.onSuccess { error("accepted pre-cancelled request") }.onFailure {
            assertTrue(it is kotlinx.coroutines.CancellationException)
        }
        assertEquals(0, network.calls)
    }

    @Test
    fun `port requires done on legacy and accepts clean finished eof on named routes`() = runBlocking {
        val clean = RecordingNetwork(events = listOf("""{"choices":[{"delta":{"content":"x"},"finish_reason":"stop"}]}"""))
        runCatching {
            OpenAiChatProviderFactory(clean, legacyDirectRequest = true).create(resolved(), null)
                .stream(request(), ProviderCancellation { false }) {}
        }.onSuccess { error("accepted legacy clean eof without done") }.onFailure {
            assertEquals(ProviderFailureCode.STREAM_TRUNCATED, (it as ProviderException).failure.code)
        }
        val named = RecordingNetwork(events = listOf("""{"choices":[{"delta":{"content":"x"},"finish_reason":"stop"}]}"""))
        val result = OpenAiChatProviderFactory(named, key = key("qwen")).create(resolved("qwen"), null)!!
            .stream(request("qwen"), ProviderCancellation { false }) {}
        assertEquals(ProviderStreamResult("x", stopReason = ProviderStopReason.STOP), result)
        val bareDone = RecordingNetwork(events = listOf("[DONE]"))
        runCatching {
            OpenAiChatProviderFactory(bareDone, key = key("qwen")).create(resolved("qwen"), null)!!
                .stream(request("qwen"), ProviderCancellation { false }) {}
        }.onSuccess { error("accepted bare done as healthy named stream") }.onFailure {
            assertEquals(ProviderFailureCode.PROVIDER_ERROR, (it as ProviderException).failure.code)
        }
        val wrong = resolved(authMode = AuthMode.X_API_KEY)
        runCatching {
            OpenAiChatProviderFactory(RecordingNetwork()).create(wrong, null)
        }.onSuccess { error("accepted unsupported auth mode") }.onFailure {
            assertEquals(ProviderFailureCode.INVALID_AUTH_MODE, (it as ProviderException).failure.code)
        }
        Unit
    }

    @Test
    fun `prepared adapter rejects a different request configuration before network`(): Unit = runBlocking {
        val network = RecordingNetwork()
        val created = OpenAiChatProviderFactory(network, key = key("qwen")).create(resolved("qwen"), null)
        runCatching {
            created.stream(request("zhipu"), ProviderCancellation { false }) {}
        }.onSuccess { error("accepted a different configuration") }.onFailure {
            assertEquals(ProviderFailureCode.INVALID_CONFIGURATION, (it as ProviderException).failure.code)
        }
        assertEquals(0, network.calls)
    }

    private fun resolved(
        provider: String = "custom",
        model: String = "gpt-test",
        authMode: AuthMode = AuthMode.NONE,
    ): ResolvedProviderConfig {
        val vendor = VendorConfig(
            identity = VendorIdentity("v", provider),
            connection = VendorConnection("https://example.test/v1", authMode),
        )
        return ResolvedProviderConfig(
            vendor = vendor,
            profile = ModelProfile(id = "m", vendorId = "v", model = model),
            runtime = ModelRuntime(),
            providerType = provider,
            providerScope = "global",
            protocol = ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
            protocolSource = ProviderProtocolSource.MANUAL,
            route = ProviderRoute(
                baseUrl = "https://example.test/v1",
                endpoint = "https://example.test/v1/chat/completions",
                authMode = authMode,
                credential = null,
                headers = emptyList(),
                adapterId = "openai_chat_completions",
            ),
        )
    }

    private fun key(provider: String) = com.deepstudent.services.providers.api.ProviderAdapterKey(
        provider,
        ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
        "general",
    )

    private fun request(provider: String = "custom", authMode: AuthMode = AuthMode.NONE) = ProviderAdapterRequest(
        resolved = resolved(provider = provider, authMode = authMode),
        messages = listOf(ProviderMessage(ProviderRole.USER, "hello")),
        tools = emptyList(),
        sessionId = "s",
    )
}

private class RecordingNetwork(
    private val events: List<String> = emptyList(),
    private val end: SseEnd = SseEnd.COMPLETE,
    private val status: Int = 200,
) : NetworkTransport {
    var request: NetworkRequest? = null
    var calls = 0

    override suspend fun execute(request: NetworkRequest, limits: HttpLimits) = error("unexpected execute")

    override suspend fun streamSse(
        request: NetworkRequest,
        limits: SseLimits,
        onResponse: suspend (ResponseHead) -> Unit,
        onEvent: suspend (SseEvent) -> Unit,
    ): SseEnd {
        calls++
        this.request = request
        onResponse(ResponseHead(status, emptyList()))
        events.forEach { onEvent(SseEvent(it, "message", "")) }
        return end
    }

    override fun close() = Unit
}

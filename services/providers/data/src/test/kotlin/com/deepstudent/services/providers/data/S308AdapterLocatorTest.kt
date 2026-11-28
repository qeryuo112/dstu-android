package com.deepstudent.services.providers.data

import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.core.common.logging.LogRecord
import com.deepstudent.core.common.logging.Logger
import com.deepstudent.platform.network.HttpLimits
import com.deepstudent.platform.network.HttpResponse
import com.deepstudent.platform.network.NetworkRequest
import com.deepstudent.platform.network.NetworkTransport
import com.deepstudent.platform.network.ResponseHead
import com.deepstudent.platform.network.SseEnd
import com.deepstudent.platform.network.SseEvent
import com.deepstudent.platform.network.SseLimits
import com.deepstudent.services.providers.api.AuthMode
import com.deepstudent.services.providers.api.ProviderAdapterFactory
import com.deepstudent.services.providers.api.ProviderAdapterKey
import com.deepstudent.services.providers.api.ProviderAdapterLocator
import com.deepstudent.services.providers.api.ProviderAdapterRequest
import com.deepstudent.services.providers.api.ProviderCancellation
import com.deepstudent.services.providers.api.ProviderCredential
import com.deepstudent.services.providers.api.ProviderEventSink
import com.deepstudent.services.providers.api.ProviderException
import com.deepstudent.services.providers.api.ProviderFailureCode
import com.deepstudent.services.providers.api.ProviderMessage
import com.deepstudent.services.providers.api.ProviderProtocol
import com.deepstudent.services.providers.api.ProviderRole
import com.deepstudent.services.providers.api.ProviderStopReason
import com.deepstudent.services.providers.api.ProviderStreamEvent
import com.deepstudent.services.providers.api.ProviderStreamPort
import com.deepstudent.services.providers.api.ResolvedProviderConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Scripted SSE transport capturing one outgoing request. */
private class FakeSseTransport(private val frames: List<String>, private val status: Int = 200) : NetworkTransport {
    val requests = mutableListOf<NetworkRequest>()

    override suspend fun execute(request: NetworkRequest, limits: HttpLimits): HttpResponse =
        HttpResponse(ResponseHead(status, emptyList()), "{}".toByteArray())

    override suspend fun streamSse(
        request: NetworkRequest,
        limits: SseLimits,
        onResponse: suspend (ResponseHead) -> Unit,
        onEvent: suspend (SseEvent) -> Unit,
    ): SseEnd {
        requests += request
        onResponse(ResponseHead(status, emptyList()))
        for (frame in frames) onEvent(SseEvent(frame, "", ""))
        return SseEnd.COMPLETE
    }

    override fun close() = Unit
}

private class RecordingLogger : Logger {
    val records = mutableListOf<LogRecord>()
    override fun log(record: LogRecord) {
        records += record
    }
}

class S308ChatAdapterFactoryTest {
    private val frames = listOf(
        """{"choices":[{"delta":{"content":"ack"},"finish_reason":"stop"}]}""",
        "[DONE]",
    )

    @Test
    fun `serves the five members through the shared port with the relay boundary`() = runBlocking {
        for (provider in RelayBodyDialect.MEMBERS) {
            val transport = FakeSseTransport(frames)
            val resolved = s308Resolved(provider, baseUrl = "https://${provider.replace("_", "-")}.example/v1")
            val locator = S308AdapterLocator(transport, allowLoopback = false, version = "0.11.0")
            val factory = checkNotNull(locator.find(resolved))
            assertEquals(ProviderAdapterKey(provider, ProviderProtocol.OPENAI_CHAT_COMPLETIONS, "general"), factory.key)
            val port = factory.create(resolved, s308Credential())
            val request = ProviderAdapterRequest(resolved, listOf(ProviderMessage(ProviderRole.USER, "hello")), emptyList(), "s")
            val received = mutableListOf<ProviderStreamEvent>()
            val result = port.stream(request, ProviderCancellation { false }, { received += it })
            assertEquals("ack", result.text)
            assertEquals(ProviderStopReason.STOP, result.stopReason)
            assertEquals(1, transport.requests.size)
            assertEquals("${resolved.baseUrl}/chat/completions", transport.requests[0].url)
        }
    }

    @Test
    fun `relay dialect and custom headers reach the wire for openrouter`() = runBlocking {
        val transport = FakeSseTransport(frames)
        val resolved = s308Resolved(
            "openrouter",
            model = "gpt-6-fixture",
            baseUrl = "https://openrouter.example.com/api/v1",
            headers = listOf(com.deepstudent.services.providers.api.ProviderHeader("X-Fixture-Trace", "s308")),
            reasoning = s308Reasoning("max"),
        )
        val factory = checkNotNull(S308AdapterLocator(transport).find(resolved))
        val port = factory.create(resolved, s308Credential())
        port.stream(
            ProviderAdapterRequest(resolved, listOf(ProviderMessage(ProviderRole.USER, "hello")), emptyList(), null),
            ProviderCancellation {
                false
            },
            {},
        )
        val sent = transport.requests.single()
        val body = JsonValue.parse(sent.body!!.decodeToString()) as JsonValue.Object
        assertEquals("max", (body["reasoning"] as JsonValue.Object).s308Text("effort"))
        assertNull(body.s308Text("reasoning_effort"))
        assertEquals("s308", sent.headers.single { it.name == "x-fixture-trace" }.value)
    }

    @Test
    fun `opencode go carries identity headers with the sanitized session`() = runBlocking {
        val transport = FakeSseTransport(frames)
        val resolved = s308Resolved("opencode_go", baseUrl = "https://opencode.ai/inference/go", vendorId = OpenCodeGoCatalog.VENDOR_ID)
        val factory = checkNotNull(S308AdapterLocator(transport).find(resolved))
        val port = factory.create(resolved, s308Credential())
        port.stream(
            ProviderAdapterRequest(resolved, listOf(ProviderMessage(ProviderRole.USER, "hello")), emptyList(), "fixture session_01:🧪 go"),
            ProviderCancellation { false },
            {},
        )
        val sent = transport.requests.single()
        assertEquals("DeepStudent/0.11.0", sent.headers.single { it.name.lowercase() == "user-agent" }.value)
        assertEquals("fixturesession_01:go", sent.headers.single { it.name.lowercase() == "x-opencode-session" }.value)
    }

    @Test
    fun `lifecycle logs carry correlation, stage and codes without secrets`() = runBlocking {
        val transport = FakeSseTransport(frames)
        val logger = RecordingLogger()
        val resolved = s308Resolved("openrouter", baseUrl = "https://openrouter.example.com/api/v1")
        val factory = checkNotNull(S308AdapterLocator(transport, logger = logger).find(resolved))
        val port = factory.create(resolved, s308Credential())
        port.stream(
            ProviderAdapterRequest(resolved, listOf(ProviderMessage(ProviderRole.USER, "hello")), emptyList(), null),
            ProviderCancellation {
                false
            },
            {},
        )
        val stages = logger.records.filter { it.event.startsWith("providers.s308.relay") }.map { it.event.substringAfterLast('.') }
        assertEquals(listOf("started", "completed"), stages)
        for (record in logger.records.filter { it.event.startsWith("providers.s308") }) {
            assertNotNull(record.correlationId)
            assertEquals("openrouter", (record.fields["category"] as JsonValue.Text).value)
        }
        val transcript = logger.records.joinToString("\n") { it.toString() }
        assertTrue("https://openrouter.example.com" !in transcript)
        assertTrue("Bearer" !in transcript)
    }

    @Test
    fun `failures keep the fixed codes of the shared port`() = runBlocking {
        val transport = FakeSseTransport(emptyList())
        val resolved = s308Resolved("custom", baseUrl = "https://relay.example.com/v1")
        val factory = checkNotNull(S308AdapterLocator(transport).find(resolved))
        val port = factory.create(resolved, s308Credential())
        val failure = runCatching {
            port.stream(
                ProviderAdapterRequest(resolved, listOf(ProviderMessage(ProviderRole.USER, "hello")), emptyList(), null),
                ProviderCancellation {
                    false
                },
                {},
            )
        }.exceptionOrNull()
        assertTrue(failure is ProviderException)
        assertEquals(ProviderFailureCode.STREAM_TRUNCATED, (failure as ProviderException).failure.code)
    }
}

private fun s308Credential() = com.deepstudent.services.providers.api.ProviderCredential.fromUtf8("s308-synthetic-bearer".toCharArray())

class S308ResponsesAdapterFactoryTest {
    private val frames = listOf(
        """{"type":"response.output_text.delta","delta":"哈"}""",
        """{"type":"response.completed","response":{"output_text":"哈"}}""",
    )

    private fun responsesResolved(provider: String = "custom", vendorId: String = "fixture-vendor") = s308Resolved(
        provider,
        baseUrl = if (provider == "opencode_go") "https://opencode.ai/inference/go/openai/v1" else "https://relay.example.com/v1",
        endpoint =
        (
            if (provider ==
                "opencode_go"
            ) {
                "https://opencode.ai/inference/go/openai/v1"
            } else {
                "https://relay.example.com/v1"
            }
            ) + "/responses",
        protocol = ProviderProtocol.OPENAI_RESPONSES,
        vendorId = vendorId,
    )

    @Test
    fun `serves explicit responses routing with byok headers`() = runBlocking {
        val transport = FakeSseTransport(frames)
        val resolved = responsesResolved()
        val factory = checkNotNull(S308AdapterLocator(transport).find(resolved))
        assertEquals(ProviderAdapterKey("custom", ProviderProtocol.OPENAI_RESPONSES, "general"), factory.key)
        val port = factory.create(resolved, s308Credential())
        val received = mutableListOf<ProviderStreamEvent>()
        val result = port.stream(
            ProviderAdapterRequest(resolved, listOf(ProviderMessage(ProviderRole.USER, "hello")), emptyList(), "session"),
            ProviderCancellation { false },
            { received += it },
        )
        assertEquals("哈", result.text)
        val sent = transport.requests.single()
        assertEquals(resolved.endpoint, sent.url)
        assertEquals("application/json", sent.headers.single { it.name == "content-type" }.value)
        val body = JsonValue.parse(sent.body!!.decodeToString()) as JsonValue.Object
        assertEquals("session", body.s308Text("prompt_cache_key"))
    }

    @Test
    fun `bearer credentials reach only the resolved endpoint`() = runBlocking {
        val transport = FakeSseTransport(frames)
        val resolved = responsesResolved()
        val credential = com.deepstudent.services.providers.api.ProviderCredential.fromUtf8("s308-synthetic-bearer".toCharArray())
        val factory =
            S308ResponsesAdapterFactory(
                transport,
                ProviderAdapterKey("custom", ProviderProtocol.OPENAI_RESPONSES, "general"),
                allowLoopback = false,
            )
        val port = factory.create(resolved, credential)
        port.stream(
            ProviderAdapterRequest(resolved, listOf(ProviderMessage(ProviderRole.USER, "hello")), emptyList(), null),
            ProviderCancellation {
                false
            },
            {},
        )
        credential.close()
        assertEquals("Bearer s308-synthetic-bearer", transport.requests.single().headers.single { it.name == "authorization" }.value)
    }

    @Test
    fun `codex auth and cross-protocol keys are rejected before any network call`() {
        val transport = FakeSseTransport(emptyList())
        val codex = s308Resolved("custom", protocol = ProviderProtocol.OPENAI_RESPONSES, authMode = AuthMode.OPENAI_CODEX_OAUTH)
        val factory = S308ResponsesAdapterFactory(transport, ProviderAdapterKey("custom", ProviderProtocol.OPENAI_RESPONSES, "general"))
        val failure = runCatching { runBlocking { factory.create(codex, null) } }.exceptionOrNull()
        assertEquals(ProviderFailureCode.INVALID_AUTH_MODE, (failure as ProviderException).failure.code)
        assertEquals(0, transport.requests.size)
    }

    @Test
    fun `opencode go responses carry identity headers`() = runBlocking {
        val transport = FakeSseTransport(frames)
        val resolved = responsesResolved("opencode_go", vendorId = OpenCodeGoCatalog.VENDOR_ID)
        val factory = checkNotNull(S308AdapterLocator(transport).find(resolved))
        val port = factory.create(resolved, s308Credential())
        port.stream(
            ProviderAdapterRequest(resolved, listOf(ProviderMessage(ProviderRole.USER, "hello")), emptyList(), "fixture session_01:🧪 go"),
            ProviderCancellation {
                false
            },
            {},
        )
        val sent = transport.requests.single()
        assertEquals("DeepStudent/0.11.0", sent.headers.single { it.name.lowercase() == "user-agent" }.value)
        assertEquals("fixturesession_01:go", sent.headers.single { it.name.lowercase() == "x-opencode-session" }.value)
    }
}

class S308AdapterLocatorFallbackTest {
    @Test
    fun `non members and native protocols use only explicitly injected factories`() {
        val transport = FakeSseTransport(emptyList())
        val qwen = ProviderAdapterFactoryForTest(ProviderAdapterKey("qwen", ProviderProtocol.OPENAI_CHAT_COMPLETIONS, "qwen"))
        val anthropic = ProviderAdapterFactoryForTest(ProviderAdapterKey("custom", ProviderProtocol.ANTHROPIC_MESSAGES, "anthropic"))
        val locator = S308AdapterLocator(
            transport,
            base = ProviderAdapterLocator { resolved ->
                listOf(qwen, anthropic).firstOrNull { it.key.providerType == resolved.providerType && it.key.protocol == resolved.protocol }
            },
        )
        val native = s308Resolved("custom", protocol = ProviderProtocol.ANTHROPIC_MESSAGES)
        assertEquals(qwen, locator.find(s308Resolved("qwen")))
        assertEquals(anthropic, locator.find(native))
        assertNull(S308AdapterLocator(transport).find(native))
        assertNull(locator.find(s308Resolved("custom", protocol = ProviderProtocol.GOOGLE_GENERATE_CONTENT)))
    }

    private class ProviderAdapterFactoryForTest(override val key: ProviderAdapterKey) : ProviderAdapterFactory {
        override suspend fun create(resolved: ResolvedProviderConfig, credential: ProviderCredential?): ProviderStreamPort =
            error("not used")
    }
}

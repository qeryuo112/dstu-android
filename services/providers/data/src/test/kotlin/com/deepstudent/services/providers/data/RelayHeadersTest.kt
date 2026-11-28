package com.deepstudent.services.providers.data

import com.deepstudent.platform.network.NetworkHeader
import com.deepstudent.platform.network.NetworkRequest
import com.deepstudent.services.providers.api.ProviderHeader
import org.junit.Assert.assertEquals
import org.junit.Test

class RelayHeadersTest {
    @Test
    fun `merges configured headers absent-only and lowercases names`() {
        val headers = mutableListOf(NetworkHeader("Content-Type", "application/json"), NetworkHeader("Authorization", "Bearer k"))
        RelayHeaders.mergeConfigured(
            headers,
            listOf(ProviderHeader("Content-Type", "text/plain"), ProviderHeader("X-Fixture-Trace", "s308")),
        )
        assertEquals(listOf("Content-Type", "Authorization", "x-fixture-trace"), headers.map { it.name })
        assertEquals("application/json", headers[0].value)
        assertEquals("s308", headers[2].value)
    }

    @Test
    fun `rejects invalid names and injection values`() {
        assertEquals(false, RelayHeaders.isApplicable("bad header", "1"))
        assertEquals(false, RelayHeaders.isApplicable("X-Trace", "bad\r\ninjected"))
        assertEquals(false, RelayHeaders.isApplicable("X-Trace", "nul\u0000"))
        assertEquals(true, RelayHeaders.isApplicable("X-Trace", "ok"))
    }
}

class RelayChatTransportTest {
    private class CapturingTransport : com.deepstudent.platform.network.NetworkTransport {
        lateinit var last: NetworkRequest
        override suspend fun execute(
            request: NetworkRequest,
            limits: com.deepstudent.platform.network.HttpLimits,
        ): com.deepstudent.platform.network.HttpResponse {
            last = request
            return com.deepstudent.platform.network.HttpResponse(
                com.deepstudent.platform.network.ResponseHead(200, emptyList()),
                "{}".toByteArray(),
            )
        }

        override suspend fun streamSse(
            request: NetworkRequest,
            limits: com.deepstudent.platform.network.SseLimits,
            onResponse: suspend (com.deepstudent.platform.network.ResponseHead) -> Unit,
            onEvent: suspend (com.deepstudent.platform.network.SseEvent) -> Unit,
        ): com.deepstudent.platform.network.SseEnd {
            last = request
            return com.deepstudent.platform.network.SseEnd.COMPLETE
        }

        override fun close() = Unit
    }

    @Test
    fun `applies identity, configured headers and dialect to the outgoing body`() = kotlinx.coroutines.runBlocking {
        val transport = CapturingTransport()
        val resolved = s308Resolved(
            "openrouter",
            model = "gpt-6-fixture",
            baseUrl = "https://openrouter.example.com/api/v1",
            headers = listOf(ProviderHeader("X-Fixture-Trace", "s308")),
            reasoning = s308Reasoning("max"),
        )
        val session = RelaySession().apply { value = "fixture session_01:🧪 go" }
        val relay = RelayChatTransport(transport, resolved, "0.11.0", session)
        val body = """{"model":"gpt-6-fixture","reasoning_effort":"xhigh"}""".toByteArray()
        relay.execute(
            NetworkRequest(
                "https://loopback.invalid/chat/completions",
                "POST",
                listOf(NetworkHeader("Content-Type", "application/json")),
                body,
            ),
        )
        val sent = transport.last
        assertEquals(listOf("content-type", "x-fixture-trace"), sent.headers.map { it.name.lowercase() })
        assertEquals("""{"model":"gpt-6-fixture","reasoning":{"effort":"max"}}""", sent.body!!.decodeToString())
    }

    @Test
    fun `go roots add the task identity without a session`() = kotlinx.coroutines.runBlocking {
        val transport = CapturingTransport()
        val resolved = s308Resolved(
            "opencode_go",
            baseUrl = "https://opencode.ai/inference/go",
            headers = listOf(ProviderHeader("X-Fixture-Go", "1")),
        )
        val relay = RelayChatTransport(transport, resolved, "0.11.0", RelaySession())
        relay.execute(NetworkRequest("https://loopback.invalid/v1/chat/completions", "POST", emptyList(), "{}".toByteArray()))
        val names = transport.last.headers.map { it.name }
        assertEquals(listOf("user-agent", "x-opencode-session", "x-fixture-go"), names)
        assertEquals("DeepStudent/0.11.0", transport.last.headers[0].value)
        assertEquals(OpenCodeGoCatalog.TASK_SESSION_ID, transport.last.headers[1].value)
    }

    @Test
    fun `go roots sanitize the supplied session value`() = kotlinx.coroutines.runBlocking {
        val transport = CapturingTransport()
        val resolved = s308Resolved("opencode_go", baseUrl = "https://opencode.ai/zen/go/v1")
        val relay = RelayChatTransport(transport, resolved, "0.11.0", RelaySession().apply { value = "fixture session_01:🧪 go" })
        relay.execute(NetworkRequest("https://loopback.invalid/v1/chat/completions", "POST", emptyList(), "{}".toByteArray()))
        assertEquals("fixturesession_01:go", transport.last.headers[1].value)
    }

    @Test
    fun `non go members get no identity headers`() = kotlinx.coroutines.runBlocking {
        val transport = CapturingTransport()
        val resolved = s308Resolved("ollama", baseUrl = "https://fixture-ollama.invalid:11434")
        val relay = RelayChatTransport(transport, resolved, "0.11.0", RelaySession())
        relay.execute(NetworkRequest("https://loopback.invalid/v1/chat/completions", "POST", emptyList(), "{}".toByteArray()))
        assertEquals(0, transport.last.headers.size)
    }
}

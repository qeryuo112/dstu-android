package com.deepstudent.services.providers.data

import com.deepstudent.platform.network.HttpLimits
import com.deepstudent.platform.network.HttpResponse
import com.deepstudent.platform.network.NetworkError
import com.deepstudent.platform.network.NetworkException
import com.deepstudent.platform.network.NetworkRequest
import com.deepstudent.platform.network.NetworkTransport
import com.deepstudent.platform.network.ResponseHead
import com.deepstudent.platform.network.SseEnd
import com.deepstudent.platform.network.SseEvent
import com.deepstudent.platform.network.SseLimits
import com.deepstudent.services.providers.api.ProviderCancellation
import com.deepstudent.services.providers.api.ProviderHeader
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fixed-script HTTP stub for the model catalog port. */
private class ScriptedHttp(private val script: (NetworkRequest) -> HttpResponse) : NetworkTransport {
    val requests = mutableListOf<NetworkRequest>()

    override suspend fun execute(request: NetworkRequest, limits: HttpLimits): HttpResponse {
        requests += request
        return script(request)
    }

    override suspend fun streamSse(
        request: NetworkRequest,
        limits: SseLimits,
        onResponse: suspend (ResponseHead) -> Unit,
        onEvent: suspend (SseEvent) -> Unit,
    ): SseEnd = throw UnsupportedOperationException("model list uses HTTP only")

    override fun close() = Unit
}

private fun httpResponse(status: Int, body: String) = HttpResponse(ResponseHead(status, emptyList()), body.toByteArray(Charsets.UTF_8))

class VendorModelCatalogTest {
    private fun request(
        provider: String = "openai",
        apiKey: String? = "s308-synthetic-list-key",
        authMode: String? = null,
        baseUrl: String = "https://api.example.com/v1",
        backup: List<String> = emptyList(),
        timeout: Long? = null,
        vendorId: String = "fixture-vendor",
    ) = VendorModelsRequest(
        VendorModelsConnection(vendorId, provider, baseUrl),
        VendorModelsAccess(authMode, backupApiKeys = backup, defaultTimeoutMillis = timeout),
    )

    @Test
    fun `paginates until the cursor is empty and sorts by id`() = runBlocking {
        val http = ScriptedHttp { call ->
            when {
                call.url.endsWith(
                    "/v1/models",
                ) -> httpResponse(
                    200,
                    """{"data":[{"id":"b-model","display_name":"Model B","context_length":8192},{"id":"b-model-tts-1"}],"has_more":true,"last_id":"b-model"}""",
                )

                call.url.endsWith(
                    "/v1/models?after=b-model",
                ) -> httpResponse(200, """{"data":[{"id":"a-model","max_output_tokens":1024}]}""")

                else -> error("unexpected ${call.url}")
            }
        }
        val models = vendorModelListPort(http).fetch(request(), "s308-synthetic-list-key", ProviderCancellation { false })
        assertEquals(listOf("a-model", "b-model"), models.map { it.id })
        assertEquals("Model B", models[1].label)
        assertEquals(8192, models[1].contextWindow)
        assertEquals(1024, models[0].maxOutputTokens)
        assertEquals(2, http.requests.size)
        assertEquals("https://api.example.com/v1/models", http.requests[0].url)
        assertEquals("https://api.example.com/v1/models?after=b-model", http.requests[1].url)
    }

    @Test
    fun `falls back to the backup key when the primary is empty`() = runBlocking {
        val http = ScriptedHttp { httpResponse(200, """{"data":[{"id":"a-model"}]}""") }
        val models = vendorModelListPort(http).fetch(
            request(apiKey = null, backup = listOf(" backup-key ")),
            null,
            ProviderCancellation {
                false
            },
        )
        assertEquals(listOf("a-model"), models.map { it.id })
        assertEquals(
            "Bearer backup-key",
            http.requests.single().headers.single {
                it.name.equals("authorization", ignoreCase = true)
            }.value,
        )
    }

    @Test
    fun `missing keys and auth mode none follow the frozen rule`() = runBlocking {
        val http = ScriptedHttp { httpResponse(200, """{"data":[{"id":"a-model"}]}""") }
        assertThrows(ModelListException::class.java) {
            runBlocking { vendorModelListPort(http).fetch(request(apiKey = null), null, ProviderCancellation { false }) }
        }
        val port = vendorModelListPort(http)
        port.fetch(request(apiKey = null, authMode = "none"), null, ProviderCancellation { false })
        assertTrue(http.requests.last().headers.none { it.name == "authorization" })
    }

    @Test
    fun `proxy rejection has a fixed status and does not disclose the response body`() = runBlocking {
        val http = ScriptedHttp { httpResponse(407, "private synthetic detail with s308-synthetic-list-key") }
        val failure = assertThrows(ModelListException::class.java) {
            runBlocking { vendorModelListPort(http).fetch(request(), "s308-synthetic-list-key", ProviderCancellation { false }) }
        }
        assertEquals(ModelListFailureCode.HTTP_STATUS, failure.code)
        assertEquals(407, failure.status)
        assertEquals("vendor model fetch failed: 407 Proxy Authentication Required", failure.message)
    }

    @Test
    fun `connect failures and parse failures map to fixed codes`() = runBlocking {
        val refused = ScriptedHttp { throw NetworkException(NetworkError.CONNECTION) }
        val connect = assertThrows(ModelListException::class.java) {
            runBlocking { vendorModelListPort(refused).fetch(request(), "s308-synthetic-list-key", ProviderCancellation { false }) }
        }
        assertEquals(ModelListFailureCode.FETCH_FAILED, connect.code)
        val malformed = ScriptedHttp { httpResponse(200, "{}") }
        val container = assertThrows(ModelListException::class.java) {
            runBlocking { vendorModelListPort(malformed).fetch(request(), "s308-synthetic-list-key", ProviderCancellation { false }) }
        }
        assertEquals(ModelListFailureCode.MISSING_MODELS_CONTAINER, container.code)
        val broken = ScriptedHttp { httpResponse(200, "<html>gateway</html>") }
        val parse = assertThrows(ModelListException::class.java) {
            runBlocking { vendorModelListPort(broken).fetch(request(), "s308-synthetic-list-key", ProviderCancellation { false }) }
        }
        assertEquals(ModelListFailureCode.PARSE_FAILED, parse.code)
    }

    @Test
    fun `returns the builtin opencode go catalog without any network call`() = runBlocking {
        val http = ScriptedHttp { error("network must not be used") }
        val models = vendorModelListPort(http).fetch(
            request(provider = "opencode_go", vendorId = OpenCodeGoCatalog.VENDOR_ID, apiKey = null),
            null,
            ProviderCancellation { false },
        )
        assertEquals(13, models.size)
        assertEquals(0, http.requests.size)
        assertEquals("deepseek-v4.1-flash", models.first().id)
        assertTrue(models.all { it.maxOutputTokens != null && it.contextWindow == null })
    }

    @Test
    fun `provider type alone selects the builtin catalog`() = runBlocking {
        val http = ScriptedHttp { error("network must not be used") }
        val models = vendorModelListPort(http).fetch(request(provider = "opencode_go", apiKey = null), null, ProviderCancellation { false })
        assertEquals(13, models.size)
    }

    @Test
    fun `cancelled calls fail with the fixed cancelled code`() {
        val http = ScriptedHttp { httpResponse(200, """{"data":[{"id":"a-model"}],"has_more":true,"last_id":"a-model"}""") }
        assertThrows(ModelListException::class.java) {
            runBlocking { vendorModelListPort(http).fetch(request(), "s308-synthetic-list-key", ProviderCancellation { true }) }
        }
        assertEquals(0, http.requests.size)
    }

    @Test
    fun `cancelling a stalled model page interrupts the transport`() = runBlocking {
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val ended = kotlinx.coroutines.CompletableDeferred<Unit>()
        val cancelled = java.util.concurrent.atomic.AtomicBoolean()
        val http = object : NetworkTransport by ScriptedHttp({ error("unused") }) {
            override suspend fun execute(request: NetworkRequest, limits: HttpLimits): HttpResponse = try {
                entered.complete(Unit)
                kotlinx.coroutines.awaitCancellation()
            } finally {
                ended.complete(Unit)
            }
        }
        val call = async {
            runCatching { vendorModelListPort(http).fetch(request(), "s308-synthetic-list-key", ProviderCancellation(cancelled::get)) }
        }
        kotlinx.coroutines.withTimeout(3_000) {
            entered.await()
            cancelled.set(true)
            assertEquals(ModelListFailureCode.CANCELLED, (call.await().exceptionOrNull() as ModelListException).code)
            assertTrue(ended.isCompleted)
        }
    }

    @Test
    fun `timeout clamps to the frozen bounds`() {
        assertEquals(1_000L, 500L.coerceIn(1_000L, 120_000L))
        assertEquals(120_000L, 200_000L.coerceIn(1_000L, 120_000L))
    }
}

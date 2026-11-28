package com.deepstudent.app.runtime

import com.deepstudent.app.BuildConfig
import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.core.common.logging.LogRecord
import com.deepstudent.core.common.logging.Logger
import com.deepstudent.platform.network.HttpLimits
import com.deepstudent.platform.network.HttpResponse
import com.deepstudent.platform.network.NetworkLog
import com.deepstudent.platform.network.NetworkPhase
import com.deepstudent.platform.network.NetworkRequest
import com.deepstudent.platform.network.NetworkTransport
import com.deepstudent.platform.network.OkHttpNetworkTransport
import com.deepstudent.platform.network.ResponseHead
import com.deepstudent.platform.network.SseEnd
import com.deepstudent.platform.network.SseEvent
import com.deepstudent.platform.network.SseLimits
import com.deepstudent.services.providers.api.CodexRequestAuth
import com.deepstudent.services.providers.api.CodexRequestAuthPort
import com.deepstudent.services.providers.api.ProviderCallAccess
import com.deepstudent.services.providers.api.ProviderCancellation
import com.deepstudent.services.providers.api.ProviderCredential
import com.deepstudent.services.providers.api.ProviderCredentialStore
import com.deepstudent.services.providers.api.ProviderEventSink
import com.deepstudent.services.providers.api.ProviderMessage
import com.deepstudent.services.providers.api.ProviderProtocol
import com.deepstudent.services.providers.api.ProviderRawConfig
import com.deepstudent.services.providers.api.ProviderResponsesHistory
import com.deepstudent.services.providers.api.ProviderResult
import com.deepstudent.services.providers.api.ProviderRole
import com.deepstudent.services.providers.api.ProviderSelection
import com.deepstudent.services.providers.api.ProviderStreamEvent
import com.deepstudent.services.providers.api.ProviderStreamResult
import com.deepstudent.services.providers.api.ProviderTool
import com.deepstudent.services.providers.api.ResponsesMetadataKind
import com.deepstudent.services.providers.backend.ProviderConfigResolver
import com.deepstudent.services.providers.backend.ProviderRawConfigDecoder
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import java.net.URI
import java.nio.file.Files
import java.nio.file.Paths
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

internal object S305ReplayFixture {
    private val data = JsonValue.parseObject(checkNotNull(javaClass.getResource("/s3-05-responses-oracle.json")).readText())
    val cases = data.array("cases").map { S305ReplayCase(it as JsonValue.Object) }
    val primary = cases.filter { it.stream != null }
    val token = data.text("token")
    val account = data.text("codexAccount")

    fun case(id: String) = cases.single { it.id == id }
}

internal class S305ReplayCase(private val data: JsonValue.Object) {
    val id = data.text("id")
    val provider = data.text("provider")
    val auth = data.text("auth")
    val codex = auth == "openai_codex_oauth"
    val session = (data["session"] as? JsonValue.Text)?.value
    val request = data.obj("request")
    val endpoint = request.text("url")
    val stream = data["stream"] as? JsonValue.Object
    val messages = data.array("messages").map { message(it as JsonValue.Object) }
    val tools = data.array("tools").map { it as JsonValue.Object }
        .filter { it.text("type") == "function" }.map { function(it.obj("function")) }
    val adapter = data.text("adapter")
    val expectedHistoryScope = data.text("historyScope")
    private val input = data.obj("input")

    fun selection(baseOverride: String? = null): ProviderSelection {
        val vendor = JsonValue.Object.Builder()
            .put("id", "fixture-vendor").put("providerType", provider).put("name", provider)
            .put("baseUrl", baseOverride ?: input.text("baseUrl")).put("authMode", auth)
            .putIfNotNull("credentialKey", if (auth == "bearer_api_key") JsonValue.text(CREDENTIAL_REF) else null)
            .putIfNotNull("supportsOpenaiResponses", input["supportsOpenaiResponses"])
            .put("headers", data.obj("headers")).build()
        val raw = ProviderRawConfig(JsonValue.obj("vendor" to vendor, "profile" to profile()).literal())
        return ProviderRawConfigDecoder.decodeSelection(raw).s305Success()
    }

    fun reply(): ProviderReplayReply {
        val body = checkNotNull(stream).array("frames").joinToString(separator = "") { raw ->
            raw as JsonValue.Object
            val event = (raw["event"] as? JsonValue.Text)?.value?.let { "event: $it\n" }.orEmpty()
            "${event}data: ${checkNotNull(raw["data"]).literal()}\n\n"
        }
        return ProviderReplayReply(200, "text/event-stream", body, keepOpen = true)
    }

    private fun profile() = JsonValue.Object.Builder()
        .put("id", "fixture-profile").put("vendorId", "fixture-vendor")
        .put("model", input.text("model")).put("label", "Responses fixture")
        .put("adapter", input.text("modelAdapter")).put("providerScope", provider)
        .put("enabled", true).put("status", "active").put("isBuiltin", false)
        .putIfNotNull("maxTokensLimit", input["maxTokensLimit"])
        .put("parameters", parameters()).put("reasoning", reasoning())
        .put("features", features()).put("capabilities", capabilities()).build()

    private fun features() = JsonValue.Object.Builder()
        .put("reasoning", checkNotNull(input["supportsReasoning"]))
        .put("embeddings", false).put("imageGeneration", false).put("reranking", false).put("favorite", false).build()

    private fun capabilities() = JsonValue.Object.Builder()
        .put("text", true).put("streaming", true).put("tools", checkNotNull(input["supportsTools"]))
        .put("images", true).put("audio", false).put("video", false).build()

    private fun parameters(): JsonValue.Object {
        val rawTools = data.array("tools").filter { (it as JsonValue.Object).text("type") != "function" }
        val options = JsonValue.Object.Builder().putAll(input.obj("extraBody"))
        if (rawTools.isNotEmpty()) options.put("tools", JsonValue.array(rawTools))
        return JsonValue.Object.Builder().put("extraBody", options.build())
            .s305Copy(input, "maxOutputTokens", "temperature")
            .putIfNotNull("topP", input["topPOverride"]).putIfNotNull("frequencyPenalty", input["frequencyPenaltyOverride"]).build()
    }

    private fun reasoning() = JsonValue.Object.Builder()
        .s305Copy(input, "reasoningEffort", "thinkingBudget", "isReasoning", "thinkingEnabled")
        .putIfNotNull("presencePenalty", input["presencePenaltyOverride"]).build()

    private fun message(item: JsonValue.Object): ProviderMessage {
        val content = item["content"]
        val history = ProviderResponsesHistory(
            contentPartsJson = (content as? JsonValue.Array)?.literal(),
            reasoningItemJson = item["response_reasoning_item"]?.literal(),
            webSearchItemsJson = item["response_web_search_items"]?.literal(),
        )
        return ProviderMessage(
            role = ProviderRole.valueOf(item.text("role").uppercase(Locale.ROOT)),
            text = (content as? JsonValue.Text)?.value.orEmpty(),
            toolCallId = (item["tool_call_id"] as? JsonValue.Text)?.value,
            toolCallsJson = item["tool_calls"]?.literal(),
            reasoningContent = (item["reasoning_content"] as? JsonValue.Text)?.value,
            responses = history,
        )
    }

    private fun function(item: JsonValue.Object) = ProviderTool(
        item.text("name"),
        item.text("description"),
        checkNotNull(item["parameters"]).literal(),
        (item["strict"] as? JsonValue.Bool)?.value,
    )

    private companion object {
        const val CREDENTIAL_REF = "provider.chat.s305-fixture"
    }
}

internal class S305ReplaySession(val case: S305ReplayCase, peers: List<ProviderReplayPeer>, val auth: S305ReplayAuth = S305ReplayAuth()) :
    AutoCloseable {
    private val log = S305ReplayLogger()
    val phases = CopyOnWriteArrayList<NetworkPhase>()
    private val network = S305SequenceNetwork(case.endpoint, peers, OkHttpNetworkTransport(NetworkLog { phases += it.phase }))
    private val credentialReads = AtomicInteger()
    private val buffers = CopyOnWriteArrayList<ByteArray>()
    private val resolver = ProviderConfigResolver()
    private val access = ProviderCallAccess(
        ProviderCredentialStore {
            credentialReads.incrementAndGet()
            val bytes = S305ReplayFixture.token.toByteArray().also { buffers += it }
            ProviderResult.Success(ProviderCredential.takeOwnership(bytes))
        },
        mainProviderAdapters(network, log, allowLoopback = false, codexAuth = auth),
    )
    val requests: Int get() = network.requests

    suspend fun call(
        cancellation: ProviderCancellation = ProviderCancellation { false },
        selection: ProviderSelection = case.selection(),
        messages: List<ProviderMessage> = case.messages,
        tools: List<ProviderTool> = case.tools,
        onEvent: suspend (ProviderStreamEvent) -> Unit = {},
    ): ProviderResult<ProviderStreamResult> = withTimeout(5_000) {
        val resolved = resolver.resolve(selection).s305Success()
        assertEquals(case.endpoint, resolved.endpoint)
        assertEquals(case.adapter, resolved.adapterId)
        assertEquals(ProviderProtocol.OPENAI_RESPONSES, resolved.protocol)
        resolver.call(selection, messages, tools, case.session, cancellation, access, ProviderEventSink(onEvent))
    }

    fun assertWireRequest(captured: ProviderReplayCapture, checkBody: Boolean = true) {
        assertEquals("POST", captured.method)
        assertEquals(URI(case.endpoint).rawPath, captured.path)
        val wantedHeaders = case.request.obj("headers")
        for ((name, value) in wantedHeaders.asMap()) assertEquals(name, (value as JsonValue.Text).value, captured.headers[name])
        if (case.auth == "none") assertFalse(captured.headers.containsKey("authorization"))
        if (case.codex) assertEquals("deep-student/${BuildConfig.VERSION_NAME}", captured.headers["user-agent"])
        if (checkBody) assertEquals(case.id, case.request["body"], JsonValue.parseObject(captured.body))
    }

    fun assertRedacted() {
        assertTrue("provider credential buffer was retained", (buffers + auth.buffers).all { bytes -> bytes.all { it == 0.toByte() } })
        assertTrue("Codex must not read a BYOK credential", !case.codex || credentialReads.get() == 0)
        val sensitive = listOf(
            S305ReplayFixture.token,
            auth.rotateTo,
            S305ReplayFixture.account,
            REPLAY_PRIVATE_DETAIL,
            "fixture-s305-session",
            "协议回放",
        )
        assertTrue("missing provider diagnostics", log.records.isNotEmpty())
        assertFalse("unsafe diagnostic", log.records.any { record -> sensitive.any(record.toString()::contains) })
        assertTrue(log.records.all { it.correlationId != null && it.fields["elapsedMs"] != null })
    }

    override fun close() = network.close()
}

internal class S305ReplayAuth(var rotateTo: String = "s305-refreshed-bearer") : CodexRequestAuthPort {
    var requests = 0
        private set
    val refreshed = mutableListOf<Long>()
    val marked = mutableListOf<Long>()
    val buffers = CopyOnWriteArrayList<ByteArray>()
    var refreshResult: ProviderResult<CodexRequestAuth>? = null

    override suspend fun requestAuth(cancellation: ProviderCancellation): ProviderResult<CodexRequestAuth> {
        requests++
        return issue(S305ReplayFixture.token, 42)
    }

    override suspend fun refreshAfterUnauthorized(generation: Long, cancellation: ProviderCancellation): ProviderResult<CodexRequestAuth> {
        refreshed += generation
        return refreshResult ?: issue(rotateTo, generation + 1)
    }

    override suspend fun markReauthenticationRequired(generation: Long) {
        marked += generation
    }

    private fun issue(value: String, generation: Long): ProviderResult<CodexRequestAuth> {
        val bytes = value.toByteArray().also { buffers += it }
        return ProviderResult.Success(
            CodexRequestAuth(ProviderCredential.takeOwnership(bytes), S305ReplayFixture.account, generation, true),
        )
    }
}

private class S305SequenceNetwork(
    private val endpoint: String,
    private val peers: List<ProviderReplayPeer>,
    private val delegate: NetworkTransport,
) : NetworkTransport {
    private val count = AtomicInteger()
    val requests: Int get() = count.get()

    override suspend fun execute(request: NetworkRequest, limits: HttpLimits): HttpResponse = error("unexpected non-streaming call")

    override suspend fun streamSse(
        request: NetworkRequest,
        limits: SseLimits,
        onResponse: suspend (ResponseHead) -> Unit,
        onEvent: suspend (SseEvent) -> Unit,
    ): SseEnd {
        check(request.url == endpoint) { "Responses target differs from frozen endpoint" }
        val peer = peers.getOrNull(count.getAndIncrement()) ?: error("unexpected additional Responses POST")
        val url = peer.origin + URI(endpoint).rawPath
        return delegate.streamSse(NetworkRequest(url, request.method, request.headers, request.body), limits, onResponse, onEvent)
    }

    override fun close() = delegate.close()
}

private class S305ReplayLogger : Logger {
    val records = CopyOnWriteArrayList<LogRecord>()
    override fun log(record: LogRecord) {
        records += record
    }
}

internal fun s305Event(event: ProviderStreamEvent): JsonValue.Object = when (event) {
    is ProviderStreamEvent.TextDelta -> s305Projection("text", JsonValue.text(event.text))

    is ProviderStreamEvent.ReasoningDelta -> s305Projection("reasoning", JsonValue.text(event.text))

    is ProviderStreamEvent.ToolCallDelta -> s305Projection("tool", JsonValue.parseObject(checkNotNull(event.rawJson)))

    is ProviderStreamEvent.ResponsesMetadata -> {
        val kind = when (event.kind) {
            ResponsesMetadataKind.REASONING_ITEM -> "reasoning_item"
            ResponsesMetadataKind.WEB_SEARCH -> "web_search"
            ResponsesMetadataKind.ANNOTATIONS -> "annotations"
        }
        s305Projection(kind, JsonValue.parse(event.rawJson))
    }

    is ProviderStreamEvent.UsageReported -> s305Projection(
        "usage",
        JsonValue.Object.Builder().apply {
            event.usage.inputTokens?.let { put("input_tokens", it) }
            event.usage.outputTokens?.let { put("output_tokens", it) }
            event.usage.totalTokens?.let { put("total_tokens", it) }
            event.usage.cachedInputTokens?.let { put("input_tokens_details", JsonValue.Object.Builder().put("cached_tokens", it).build()) }
            event.usage.reasoningTokens?.let {
                put("output_tokens_details", JsonValue.Object.Builder().put("reasoning_tokens", it).build())
            }
        }.build(),
    )

    is ProviderStreamEvent.Completed -> s305Projection("completed")
}

private fun s305Projection(kind: String, value: JsonValue? = null) =
    JsonValue.Object.Builder().put("kind", kind).putIfNotNull("value", value).build()

internal fun <T> ProviderResult<T>.s305Success(): T = (this as? ProviderResult.Success)?.value ?: error("Responses call failed: $this")

internal fun s305Capture(case: S305ReplayCase, operation: String, value: JsonValue.Object) {
    val target = System.getenv("S305_CAPTURE_DIR") ?: return
    val receipt = JsonValue.Object.Builder().put("case", case.id).put("provider", case.provider)
        .put("operation", operation).put("value", value).build().canonical()
    Files.write(Paths.get(target).resolve("s305-${case.id}-$operation.json"), "$receipt\n".encodeToByteArray())
}

private fun JsonValue.Object.Builder.s305Copy(from: JsonValue.Object, vararg keys: String): JsonValue.Object.Builder = apply {
    keys.forEach { putIfNotNull(it, from[it]) }
}

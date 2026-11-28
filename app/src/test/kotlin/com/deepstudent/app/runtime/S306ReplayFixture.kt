package com.deepstudent.app.runtime

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
import com.deepstudent.services.providers.api.ProviderAdapterKey
import com.deepstudent.services.providers.api.ProviderAdapterRequest
import com.deepstudent.services.providers.api.ProviderCallAccess
import com.deepstudent.services.providers.api.ProviderCancellation
import com.deepstudent.services.providers.api.ProviderCredential
import com.deepstudent.services.providers.api.ProviderCredentialStore
import com.deepstudent.services.providers.api.ProviderEventSink
import com.deepstudent.services.providers.api.ProviderException
import com.deepstudent.services.providers.api.ProviderMessage
import com.deepstudent.services.providers.api.ProviderProtocol
import com.deepstudent.services.providers.api.ProviderRawConfig
import com.deepstudent.services.providers.api.ProviderResult
import com.deepstudent.services.providers.api.ProviderRole
import com.deepstudent.services.providers.api.ProviderSelection
import com.deepstudent.services.providers.api.ProviderStreamEvent
import com.deepstudent.services.providers.api.ProviderStreamResult
import com.deepstudent.services.providers.api.ProviderTool
import com.deepstudent.services.providers.api.ResolvedProviderConfig
import com.deepstudent.services.providers.backend.ProviderConfigResolver
import com.deepstudent.services.providers.backend.ProviderRawConfigDecoder
import com.deepstudent.services.providers.data.AnthropicImageBytes
import com.deepstudent.services.providers.data.AnthropicImageSource
import com.deepstudent.services.providers.data.AnthropicProviderPort
import com.deepstudent.services.providers.data.AnthropicReplayCapture
import com.deepstudent.services.providers.data.AnthropicReplayState
import com.deepstudent.services.providers.data.AnthropicRequestSidecar
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException

/** Frozen Anthropic golden fixture recorded from the locked desktop implementation. */
internal object S306ReplayFixture {
    private val data = JsonValue.parseObject(checkNotNull(javaClass.getResource("/s3-06-anthropic-oracle.json")).readText())
    val cases = data.s306Array("cases").map { S306ReplayCase(it as JsonValue.Object, data) }
    val requestCases = cases.filter { it.streamJson == null }
    val streamCases = cases.filter { it.streamJson != null }
    val token = data.s306Text("token") ?: error("fixture token")
    val imageCache = data.s306ObjOrNull("imageCache")

    fun case(id: String) = cases.single { it.id == id }

    fun imageSource(): AnthropicImageSource = AnthropicImageSource { url ->
        imageCache?.get(url)?.let { cached ->
            AnthropicImageBytes(
                (cached as? JsonValue.Object)?.s306Text("media_type") ?: "application/octet-stream",
                (cached as? JsonValue.Object)?.s306Text("data") ?: "",
            )
        }
    }
}

internal class S306ReplayCase(private val data: JsonValue.Object, private val root: JsonValue.Object) {
    val id = data.s306Text("id") ?: error("case id")
    val provider = data.s306Text("provider") ?: error("provider")
    val auth = data.s306Text("auth") ?: error("auth")
    val request = data.s306ObjOrNull("request") ?: error("request")
    val goldenUrl = request.s306Text("url") ?: error("url")
    val routeEndpoint get() = withSuffix(baseUrl)
    val historyScope = data.s306Text("historyScope") ?: error("scope")
    val sidecarBlocks = data.s306ObjOrNull("sidecarBlocks") ?: JsonValue.Object.EMPTY
    val messages = data.s306Array("messages").map { message(it as JsonValue.Object) }
    private val declarations = data.s306Array("tools").map { it as JsonValue.Object }
    private val rawTools = declarations.takeIf { tools -> tools.any { it.containsKey("cache_control") } }
    val tools = if (rawTools != null) {
        emptyList()
    } else {
        declarations.filter { it.s306Text("type") == "function" }
            .map { function(it.s306ObjOrNull("function") ?: error("function")) }
    }
    val streamJson = data["stream"] as? JsonValue.Object
    val needsSidecar: Boolean = !sidecarBlocks.isEmpty() || imageUrlsNonEmpty()

    private val input = data.s306ObjOrNull("input") ?: error("input")
    private val baseUrl = input.s306Text("baseUrl") ?: error("baseUrl")

    fun selection(model: String? = null): ProviderSelection {
        val vendor = JsonValue.Object.Builder()
            .put("id", "fixture-vendor").put("providerType", provider).put("name", provider)
            .put("baseUrl", baseUrl.trimEnd('/') + "/").put("authMode", auth)
            .put("credentialKey", CREDENTIAL_REF)
            .put("headers", root.s306ObjOrNull("configuredHeaders") ?: JsonValue.Object.EMPTY)
            .build()
        val profile = JsonValue.Object.Builder()
            .put("id", "fixture-profile").put("vendorId", "fixture-vendor")
            .put("model", model ?: input.s306Text("model") ?: error("model"))
            .put("label", "Anthropic fixture")
            .put("adapter", input.s306Text("modelAdapter") ?: "anthropic")
            .put("providerScope", provider)
            .put("enabled", true).put("status", "active").put("isBuiltin", false)
            .put("maxTokensLimit", input["maxTokensLimit"] ?: JsonValue.Null)
            .put("parameters", parameters()).put("reasoning", reasoning())
            .put("features", features()).put("capabilities", capabilities())
            .build()
        val raw = ProviderRawConfig(JsonValue.obj("vendor" to vendor, "profile" to profile).literal())
        return ProviderRawConfigDecoder.decodeSelection(raw).s306Success()
    }

    fun sidecar(imageSource: AnthropicImageSource? = null): AnthropicRequestSidecar {
        if (sidecarBlocks.isEmpty() && imageUrlsByMessage().isEmpty() && imageSource == null) {
            return AnthropicRequestSidecar()
        }
        return AnthropicRequestSidecar(
            replay = if (sidecarBlocks.isEmpty()) {
                null
            } else {
                AnthropicReplayState(
                    historyScope,
                    sidecarBlocks.asMap().mapKeys { (key, _) -> key.toInt() }.mapValues { (_, value) -> value.literal() },
                )
            },
            imageSource = imageSource ?: S306ReplayFixture.imageSource(),
            imageUrls = imageUrlsByMessage(),
        )
    }

    fun reply(): ProviderReplayReply {
        val stream = checkNotNull(streamJson)
        val body = stream.s306Array("frames").joinToString(separator = "") { raw ->
            val frame = raw as JsonValue.Object
            val event = (frame["event"] as? JsonValue.Text)?.value?.let { "event: $it\n" }.orEmpty()
            val data = checkNotNull(frame["data"])
            "${event}data: ${(data as? JsonValue.Text)?.value ?: data.literal()}\n\n"
        }
        return ProviderReplayReply(200, "text/event-stream", body, keepOpen = true)
    }

    fun assertWireRequest(captured: ProviderReplayCapture) {
        assertEquals("POST", captured.method)
        assertEquals(URI(goldenUrl).rawPath, captured.path)
        for ((name, value) in checkNotNull(request.s306ObjOrNull("headers")).asMap()) {
            assertEquals(name, (value as JsonValue.Text).value, captured.headers[name])
        }
        assertEquals(id, request["body"], JsonValue.parseObject(captured.body))
    }

    private fun parameters(): JsonValue.Object = JsonValue.Object.Builder()
        .put("extraBody", options())
        .put("maxOutputTokens", input["maxOutputTokens"] ?: JsonValue.integer(2_048))
        .put("temperature", input["temperature"] ?: JsonValue.Number.raw("0.25"))
        .putIfNotNull("topP", input["topPOverride"])
        .putIfNotNull("topK", input["topK"])
        .build()

    private fun options(): JsonValue.Object = JsonValue.Object.Builder()
        .putAll(input.s306ObjOrNull("extraBody") ?: JsonValue.Object.EMPTY)
        .putIfNotNull("tools", rawTools?.let(JsonValue::array))
        .build()

    private fun reasoning(): JsonValue.Object = JsonValue.Object.Builder()
        .putIfNotNull("reasoningEffort", input["reasoningEffort"])
        .putIfNotNull("thinkingBudget", input["thinkingBudget"])
        .putIfNotNull("includeThoughts", input["includeThoughts"])
        .put("isReasoning", checkNotNull(input["isReasoning"]))
        .put("thinkingEnabled", checkNotNull(input["thinkingEnabled"]))
        .build()

    private fun features() = JsonValue.Object.Builder()
        .put("reasoning", checkNotNull(input["supportsReasoning"]))
        .put("embeddings", false).put("imageGeneration", false).put("reranking", false).put("favorite", false)
        .build()

    private fun capabilities() = JsonValue.Object.Builder()
        .put("text", true).put("streaming", true).put("tools", checkNotNull(input["supportsTools"]))
        .put("images", true).put("audio", false).put("video", false)
        .build()

    private fun message(item: JsonValue.Object): ProviderMessage {
        val role = ProviderRole.valueOf(item.s306Text("role")!!.uppercase())
        val content = item["content"]
        val parts = content as? JsonValue.Array
        val text = (content as? JsonValue.Text)?.value
            ?: parts?.values?.mapNotNull { part -> ((part as? JsonValue.Object)?.get("text") as? JsonValue.Text)?.value }
                ?.joinToString("")
                .orEmpty()
        return ProviderMessage(
            role = role,
            text = text,
            toolCallId = (item["tool_call_id"] as? JsonValue.Text)?.value,
            toolCallsJson = item["tool_calls"]?.literal(),
            reasoningContent = (item["reasoning_content"] as? JsonValue.Text)?.value,
        )
    }

    private fun imageUrlsNonEmpty(): Boolean = data.s306Array("messages").any { raw ->
        val content = (raw as? JsonValue.Object)?.get("content") as? JsonValue.Array ?: return@any false
        content.values.any { part -> (part as? JsonValue.Object)?.get("image_url") != null }
    }

    private fun imageUrlsByMessage(): Map<Int, List<String>> {
        val urls = mutableMapOf<Int, List<String>>()
        data.s306Array("messages").forEachIndexed { index, raw ->
            val content = (raw as? JsonValue.Object)?.get("content") as? JsonValue.Array ?: return@forEachIndexed
            val messageUrls = content.values.mapNotNull { part ->
                val imageUrl = (part as? JsonValue.Object)?.get("image_url") as? JsonValue.Object ?: return@mapNotNull null
                imageUrl.s306Text("url")
            }
            if (messageUrls.isNotEmpty()) urls[index] = messageUrls
        }
        return urls
    }

    private fun function(item: JsonValue.Object) = ProviderTool(
        item.s306Text("name") ?: error("tool name"),
        item.s306Text("description") ?: "",
        checkNotNull(item["parameters"]).literal(),
        (item["strict"] as? JsonValue.Bool)?.value,
    )

    private fun withSuffix(base: String): String = if (base.endsWith("/messages")) base else "$base/messages"

    private companion object {
        const val CREDENTIAL_REF = "provider.chat.s306-fixture"
    }
}

/** Uses the application's adapter selection; only the verified wire target is redirected to the loopback peer. */
internal class S306ReplaySession(private val case: S306ReplayCase, peers: List<ProviderReplayPeer>) : AutoCloseable {
    private val log = S306ReplayLogger()
    val phases = CopyOnWriteArrayList<NetworkPhase>()
    private val network = S306RedirectNetwork(case.goldenUrl, peers, OkHttpNetworkTransport(NetworkLog { phases += it.phase }))
    private val buffers = CopyOnWriteArrayList<ByteArray>()
    private val resolver = ProviderConfigResolver()
    private val access = ProviderCallAccess(
        ProviderCredentialStore {
            val bytes = S306ReplayFixture.token.toByteArray().also(buffers::add)
            ProviderResult.Success(ProviderCredential.takeOwnership(bytes))
        },
        mainProviderAdapters(network, log, allowLoopback = false),
    )
    val requests: Int get() = network.requests
    val logRecords: List<LogRecord> get() = log.records
    private var lastPort: AnthropicProviderPort? = null

    fun replayCaptureOrNull(): AnthropicReplayCapture? = lastPort?.replayCapture()

    suspend fun call(
        cancellation: ProviderCancellation = ProviderCancellation { false },
        selection: ProviderSelection = case.selection(),
        messages: List<ProviderMessage> = case.messages,
        tools: List<ProviderTool> = case.tools,
        withSidecar: Boolean = false,
        imageSource: AnthropicImageSource? = null,
        replay: AnthropicReplayState? = null,
        onEvent: suspend (ProviderStreamEvent) -> Unit = {},
    ): ProviderResult<ProviderStreamResult> = withTimeout(5_000) {
        val resolved = resolver.resolve(selection).s306Success()
        assertEquals(case.routeEndpoint, resolved.endpoint)
        assertEquals("anthropic", resolved.adapterId)
        assertEquals(ProviderProtocol.ANTHROPIC_MESSAGES, resolved.protocol)
        val key = ProviderAdapterKey(resolved.providerType, resolved.protocol, resolved.adapterId)
        val factory = checkNotNull(access.adapters.find(resolved))
        check(factory.key == key)
        val credential = access.credentials.read(checkNotNull(resolved.credential)).s306Success()
        try {
            val port = factory.create(resolved, credential)
            lastPort = port as? AnthropicProviderPort
            val request = ProviderAdapterRequest(resolved, messages, tools, SESSION_ID)
            try {
                val result = if ((withSidecar || replay != null) && port is AnthropicProviderPort) {
                    val sidecar = if (replay == null) case.sidecar(imageSource) else AnthropicRequestSidecar(replay = replay)
                    port.stream(request, cancellation, ProviderEventSink(onEvent), sidecar)
                } else {
                    port.stream(request, cancellation, ProviderEventSink(onEvent))
                }
                ProviderResult.Success(result)
            } catch (cancelled: CancellationException) {
                ProviderResult.Cancelled
            } catch (failure: ProviderException) {
                ProviderResult.Failure(failure.failure)
            }
        } finally {
            credential.close()
        }
    }

    fun assertRedacted() {
        assertTrue("provider credential buffer was retained", buffers.all { bytes -> bytes.all { it == 0.toByte() } })
        val sensitive = listOf(S306ReplayFixture.token, SESSION_ID, REPLAY_PRIVATE_DETAIL, "sig_round_1", "opaque_payload")
        assertTrue("missing provider diagnostics", logRecords.isNotEmpty())
        assertFalse("unsafe diagnostic", logRecords.any { record -> sensitive.any(record.toString()::contains) })
        assertTrue(logRecords.all { it.correlationId != null && it.fields["elapsedMs"] != null })
    }

    override fun close() = network.close()

    companion object {
        const val SESSION_ID = "fixture-s306-session"
    }
}

private class S306RedirectNetwork(
    private val expectedUrl: String,
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
        check(expectedUrl == request.url) { "Anthropic target differs from the frozen URL" }
        val peer = peers.getOrNull(count.getAndIncrement()) ?: error("unexpected additional Anthropic POST")
        return delegate.streamSse(
            NetworkRequest(peer.origin + URI(expectedUrl).rawPath, request.method, request.headers, request.body),
            limits,
            onResponse,
            onEvent,
        )
    }

    override fun close() = delegate.close()
}

private class S306ReplayLogger : Logger {
    val records = CopyOnWriteArrayList<LogRecord>()
    override fun log(record: LogRecord) {
        records += record
    }
}

internal fun s306Event(event: ProviderStreamEvent): JsonValue.Object = when (event) {
    is ProviderStreamEvent.TextDelta -> s306Projection("text", JsonValue.text(event.text))
    is ProviderStreamEvent.ReasoningDelta -> s306Projection("reasoning", JsonValue.text(event.text))
    is ProviderStreamEvent.ToolCallDelta -> s306Projection("tool", JsonValue.parseObject(checkNotNull(event.rawJson)))
    is ProviderStreamEvent.UsageReported -> s306Projection("usage", s306Usage(event.usage))
    is ProviderStreamEvent.Completed -> s306Projection("completed")
    else -> error("unexpected sealed event on the Anthropic path: $event")
}

private fun s306Usage(usage: com.deepstudent.services.providers.api.ProviderUsage): JsonValue.Object {
    val builder = JsonValue.Object.Builder()
        .put("input_tokens", checkNotNull(usage.inputTokens))
        .put("output_tokens", checkNotNull(usage.outputTokens))
    usage.totalTokens?.let { builder.put("total_tokens", it) }
    if (usage.cachedInputTokens != null || usage.cacheWriteInputTokens != null) {
        val details = JsonValue.Object.Builder()
        usage.cachedInputTokens?.let { details.put("cached_tokens", it) }
        usage.cacheWriteInputTokens?.let { details.put("cache_write_tokens", it) }
        builder.put("input_tokens_details", details.build())
    }
    usage.reasoningTokens?.let {
        builder.put("output_tokens_details", JsonValue.obj("reasoning_tokens" to JsonValue.integer(it)))
    }
    return builder.build()
}

private fun s306Projection(kind: String, value: JsonValue? = null) =
    JsonValue.Object.Builder().put("kind", kind).putIfNotNull("value", value).build()

internal fun <T> ProviderResult<T>.s306Success(): T = (this as? ProviderResult.Success)?.value ?: error("Anthropic call failed: $this")

internal fun JsonValue.Object.s306Text(key: String): String? = (this[key] as? JsonValue.Text)?.value

internal fun JsonValue.Object.s306ObjOrNull(key: String): JsonValue.Object? = this[key] as? JsonValue.Object

internal fun JsonValue.Object.s306Array(key: String): List<JsonValue> = (this[key] as? JsonValue.Array)?.values ?: emptyList()

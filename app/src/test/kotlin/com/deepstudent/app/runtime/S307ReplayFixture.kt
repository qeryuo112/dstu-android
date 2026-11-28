package com.deepstudent.app.runtime

import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.core.common.logging.LogRecord
import com.deepstudent.core.common.logging.Logger
import com.deepstudent.platform.network.NetworkLog
import com.deepstudent.platform.network.NetworkPhase
import com.deepstudent.platform.network.NetworkRequest
import com.deepstudent.platform.network.NetworkTransport
import com.deepstudent.platform.network.OkHttpNetworkTransport
import com.deepstudent.platform.network.ResponseHead
import com.deepstudent.platform.network.SseEnd
import com.deepstudent.platform.network.SseEvent
import com.deepstudent.platform.network.SseLimits
import com.deepstudent.services.providers.api.ProviderCallAccess
import com.deepstudent.services.providers.api.ProviderCancellation
import com.deepstudent.services.providers.api.ProviderCredential
import com.deepstudent.services.providers.api.ProviderCredentialStore
import com.deepstudent.services.providers.api.ProviderEventSink
import com.deepstudent.services.providers.api.ProviderMessage
import com.deepstudent.services.providers.api.ProviderNativeHistory
import com.deepstudent.services.providers.api.ProviderNativeMessage
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
import com.deepstudent.services.providers.data.GeminiThoughtSignatureSink
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import java.net.URI
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

internal object S307ReplayFixture {
    private val data = JsonValue.parseObject(checkNotNull(javaClass.getResource("/s3-07-gemini-oracle.json")).readText())
    val cases = data.array("cases").map { S307Case(it as JsonValue.Object, data.text("token")) }
    val token = data.text("token")
    val streamCases = cases.filter { it.stream != null }

    fun case(id: String) = cases.single { it.id == id }
}

internal class S307Case(private val data: JsonValue.Object, internal val token: String) {
    val id = data.text("id")
    val provider = data.text("provider")
    val model = data.text("model")
    val request = data.obj("request")
    val requestUrl = request.text("url")
    val crossTarget = data["requestCrossTarget"] as? JsonValue.Object
    val stream = data["stream"] as? JsonValue.Object
    val messages = data.array("messages")
    val tools = data.array("tools").map { it as JsonValue.Object }
        .filter { it.text("type") == "function" }.map { tool(it.obj("function")) }

    private fun tool(item: JsonValue.Object) = ProviderTool(
        item.text("name"),
        item.optionalText("description").orEmpty(),
        checkNotNull(item["parameters"]).literal(),
        (item["strict"] as? JsonValue.Bool)?.value,
    )
    val adapter = data.text("adapter")
    val historyScope = data.text("historyScope")
    private val input = data.obj("input")

    /** Route-shaped URL without the credential query; the port appends the encoded key. */
    val routeUrl: String
        get() = requestUrl.substringBefore("&key=")

    fun urls(crossTarget: Boolean) = listOf(if (crossTarget) checkNotNull(this.crossTarget).text("url") else requestUrl)

    fun selection(): ProviderSelection {
        val vendor = JsonValue.Object.Builder()
            .put("id", "fixture-vendor").put("providerType", provider).put("name", provider)
            .put("baseUrl", input.text("baseUrl") + "/").put("authMode", "x_api_key")
            .put("credentialKey", JsonValue.text(CREDENTIAL_REF))
            .put("headers", data.obj("headers")).build()
        val raw = ProviderRawConfig(JsonValue.obj("vendor" to vendor, "profile" to profile()).literal())
        return ProviderRawConfigDecoder.decodeSelection(raw).s307Success()
    }

    private fun profile() = JsonValue.Object.Builder()
        .put("id", "fixture-profile").put("vendorId", "fixture-vendor")
        .put("model", model).put("label", "Gemini fixture")
        .put("adapter", "google").put("providerScope", provider)
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
        val extraBody = JsonValue.Object.Builder().putAll(input.obj("extraBody"))
        input.optionalText("geminiApiVersion")?.takeIf(String::isNotEmpty)?.let { extraBody.put("gemini_api_version", it) }
        return JsonValue.Object.Builder()
            .put("extraBody", extraBody.build())
            .s307Copy(input, "maxOutputTokens", "temperature", "topK")
            .putIfNotNull("topP", input["topPOverride"])
            .putIfNotNull("frequencyPenalty", input["frequencyPenaltyOverride"]).build()
    }

    private fun reasoning() = JsonValue.Object.Builder()
        .s307Copy(input, "reasoningEffort", "thinkingBudget", "isReasoning", "thinkingEnabled", "includeThoughts")
        .putIfNotNull("presencePenalty", input["presencePenaltyOverride"]).build()

    private fun nativeParts(message: JsonValue.Object, scope: String): ProviderNativeMessage? {
        val parts = (message["content"] as? JsonValue.Array)?.values.orEmpty()
        val images = parts.mapNotNull {
            ((it as? JsonValue.Object)?.get("image_url") as? JsonValue.Object)?.optionalText("url")
        }
        val state = message.optionalText("thought_signature")?.let { signature ->
            val payload = JsonValue.obj("thoughtSignature" to JsonValue.text(signature)).literal()
            ProviderNativeHistory(ProviderProtocol.GOOGLE_GENERATE_CONTENT, scope, payload)
        }
        return if (images.isEmpty() && state == null) null else ProviderNativeMessage(images, state)
    }

    fun providerMessages(scope: String = historyScope): List<ProviderMessage> = messages.map { message ->
        val body = message as JsonValue.Object
        val content = body?.get("content")
        ProviderMessage(
            role = ProviderRole.valueOf(checkNotNull(body).text("role").uppercase(Locale.ROOT)),
            text = when (content) {
                is JsonValue.Text -> content.value

                is JsonValue.Array -> content.values.filterIsInstance<JsonValue.Object>()
                    .filter { it.text("type") == "text" }.joinToString("\n") { checkNotNull(it.text("text")) }

                else -> ""
            },
            name = body.optionalText("name"),
            toolCallId = body.optionalText("tool_call_id"),
            toolCallsJson = body["tool_calls"]?.literal(),
            nativeContent = nativeParts(body, scope),
        )
    }

    /** Frozen frames arrive as raw data payloads; [DONE] included. */
    fun reply(): ProviderReplayReply {
        val body = checkNotNull(stream).array("frames").joinToString(separator = "") { frame ->
            "data: ${(frame as JsonValue.Text).value}\n\n"
        }
        return ProviderReplayReply(200, "text/event-stream", body, keepOpen = true)
    }

    private companion object {
        const val CREDENTIAL_REF = "provider.chat.s307-fixture"
    }
}

internal class S307SequenceItem(val kind: String, val event: ProviderStreamEvent?, val signature: String?)

internal class S307ReplaySession(val case: S307Case, peers: List<ProviderReplayPeer>, crossTarget: Boolean = false) : AutoCloseable {
    private val log = S307ReplayLogger()
    val phases = CopyOnWriteArrayList<NetworkPhase>()
    private val expectedUrls = case.urls(crossTarget)
    private val network = S307LoopbackNetwork(expectedUrls, peers, OkHttpNetworkTransport(NetworkLog { phases += it.phase }))
    private val credentialReads = AtomicInteger()
    private val buffers = CopyOnWriteArrayList<ByteArray>()
    val sequence = CopyOnWriteArrayList<S307SequenceItem>()
    private val resolver = ProviderConfigResolver()
    private val historyScope = if (crossTarget) "s307-different-target" else case.historyScope
    private val signatureSink = GeminiThoughtSignatureSink { sequence.add(S307SequenceItem("signature", null, it)) }

    private val access = ProviderCallAccess(
        ProviderCredentialStore {
            credentialReads.incrementAndGet()
            val bytes = case.token.toByteArray().also { buffers += it }
            ProviderResult.Success(ProviderCredential.takeOwnership(bytes))
        },
        mainProviderAdapters(network, log, allowLoopback = false, geminiObserver = signatureSink),
    )
    val requests: Int get() = network.requests

    suspend fun call(
        cancellation: ProviderCancellation = ProviderCancellation { false },
        messages: List<ProviderMessage> = case.providerMessages(historyScope),
        tools: List<ProviderTool> = case.tools,
        onEvent: suspend (ProviderStreamEvent) -> Unit = {},
    ): ProviderResult<ProviderStreamResult> = withTimeout(5_000) {
        val resolved = resolver.resolve(case.selection()).s307Success()
        assertEquals("google", resolved.adapterId)
        assertEquals(ProviderProtocol.GOOGLE_GENERATE_CONTENT, resolved.protocol)
        resolver.call(
            case.selection(),
            messages,
            tools,
            null,
            cancellation,
            access,
            ProviderEventSink { event ->
                sequence.add(S307SequenceItem("event", event, null))
                onEvent(event)
            },
        )
    }

    val records: List<LogRecord> get() = log.records

    fun assertRedacted(expectDiagnostics: Boolean = true) {
        assertTrue("provider credential buffer was retained", buffers.all { bytes -> bytes.all { it == 0.toByte() } })
        val sensitive = listOf(S307ReplayFixture.token, "fixture-signature", REPLAY_PRIVATE_DETAIL, "协议回放")
        if (expectDiagnostics) {
            assertTrue("missing provider diagnostics", log.records.isNotEmpty())
        } else {
            assertEquals(0, credentialReads.get())
            assertTrue(log.records.isEmpty())
        }
        assertFalse("unsafe diagnostic", log.records.any { record -> sensitive.any(record.toString()::contains) })
        assertTrue(log.records.all { it.correlationId != null && it.fields["elapsedMs"] != null })
    }

    override fun close() = network.close()
}

private class S307LoopbackNetwork(
    private val expectedUrls: List<String>,
    private val peers: List<ProviderReplayPeer>,
    private val delegate: NetworkTransport,
) : NetworkTransport {
    private val count = AtomicInteger()
    val requests: Int get() = count.get()

    override suspend fun execute(request: NetworkRequest, limits: com.deepstudent.platform.network.HttpLimits) =
        error("unexpected non-streaming call")

    override suspend fun streamSse(
        request: NetworkRequest,
        limits: SseLimits,
        onResponse: suspend (ResponseHead) -> Unit,
        onEvent: suspend (SseEvent) -> Unit,
    ): SseEnd {
        val expected = expectedUrls.getOrNull(count.getAndIncrement())
            ?: error("unexpected additional Gemini POST")
        check(expected == request.url) { "Gemini target differs from the frozen route" }
        val uri = URI(request.url)
        val redirect = peer.origin + uri.rawPath + (uri.rawQuery?.let { "?$it" } ?: "")
        return delegate.streamSse(NetworkRequest(redirect, request.method, request.headers, request.body), limits, onResponse, onEvent)
    }

    private val peer: ProviderReplayPeer get() = peers.first()

    override fun close() = delegate.close()
}

private class S307ReplayLogger : Logger {
    val records = CopyOnWriteArrayList<LogRecord>()
    override fun log(record: LogRecord) {
        records += record
    }
}

internal fun s307Projection(kind: String, value: JsonValue? = null) =
    JsonValue.Object.Builder().put("kind", kind).putIfNotNull("value", value).build()

internal fun s307Event(item: S307SequenceItem): JsonValue.Object = when {
    item.signature != null -> s307Projection("signature", JsonValue.text(item.signature))

    item.event is ProviderStreamEvent.TextDelta -> s307Projection("text", JsonValue.text(item.event.text))

    item.event is ProviderStreamEvent.ReasoningDelta -> s307Projection("reasoning", JsonValue.text(item.event.text))

    item.event is ProviderStreamEvent.ToolCallDelta -> s307Projection(
        "tool",
        JsonValue.obj(
            "index" to JsonValue.integer(item.event.index.toLong()),
            // Synthetic ids are random per run; both sides normalize them to a stable marker.
            "id" to JsonValue.text(
                SYNTHETIC_ID.matches(checkNotNull(item.event.id)).let { ok ->
                    if (ok) "call-synthetic" else checkNotNull(item.event.id)
                },
            ),
            "type" to JsonValue.text(checkNotNull(item.event.type)),
            "function" to JsonValue.obj(
                "name" to JsonValue.text(checkNotNull(item.event.name)),
                "arguments" to JsonValue.text(checkNotNull(item.event.argumentsFragment)),
            ),
        ),
    )

    item.event is ProviderStreamEvent.UsageReported -> s307Projection(
        "usage",
        JsonValue.Object.Builder().apply {
            item.event.usage.inputTokens?.let { put("input_tokens", it) }
            item.event.usage.outputTokens?.let { put("output_tokens", it) }
            item.event.usage.totalTokens?.let { put("total_tokens", it) }
            item.event.usage.cachedInputTokens?.let { put("cached_tokens", it) }
            item.event.usage.reasoningTokens?.let { put("reasoning_tokens", it) }
        }.build(),
    )

    item.event is ProviderStreamEvent.Completed -> s307Projection("completed")

    else -> error("unexpected stream event $item")
}

private fun JsonValue.Object.optionalText(key: String): String? = (this[key] as? JsonValue.Text)?.value

internal fun <T> ProviderResult<T>.s307Success(): T = (this as? ProviderResult.Success)?.value ?: error("Gemini call failed: $this")

private val SYNTHETIC_ID = Regex("call-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", RegexOption.IGNORE_CASE)

private fun JsonValue.Object.Builder.s307Copy(from: JsonValue.Object, vararg keys: String): JsonValue.Object.Builder = apply {
    keys.forEach { putIfNotNull(it, from[it]) }
}

internal fun s307Capture(case: S307Case, operation: String, value: JsonValue.Object) {
    val target = System.getenv("S307_CAPTURE_DIR") ?: return
    val receipt = JsonValue.Object.Builder().put("case", case.id).put("provider", case.provider)
        .put("operation", operation).put("value", value).build().canonical()
    java.nio.file.Files.write(
        java.nio.file.Paths.get(target).resolve("s307-${case.id}-$operation.json"),
        "$receipt\n".encodeToByteArray(),
    )
}

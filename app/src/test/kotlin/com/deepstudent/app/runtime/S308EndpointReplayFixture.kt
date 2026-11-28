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
import com.deepstudent.services.providers.api.ProviderCallAccess
import com.deepstudent.services.providers.api.ProviderCancellation
import com.deepstudent.services.providers.api.ProviderCredential
import com.deepstudent.services.providers.api.ProviderCredentialStore
import com.deepstudent.services.providers.api.ProviderFailureCode
import com.deepstudent.services.providers.api.ProviderMessage
import com.deepstudent.services.providers.api.ProviderRawConfig
import com.deepstudent.services.providers.api.ProviderResult
import com.deepstudent.services.providers.api.ProviderRole
import com.deepstudent.services.providers.api.ProviderSelection
import com.deepstudent.services.providers.api.ProviderStreamEvent
import com.deepstudent.services.providers.api.ProviderStreamResult
import com.deepstudent.services.providers.backend.ProviderConfigResolver
import com.deepstudent.services.providers.backend.ProviderRawConfigDecoder
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import java.net.URI
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Frozen S3.08 oracle: custom/general/opencode_go/openrouter/ollama endpoint fixtures. */
internal object S308EndpointFixture {
    private val oracle = JsonValue.parseObject(checkNotNull(javaClass.getResource("/s3-08-endpoints-oracle.json")).readText())
    val cases: List<S308EndpointCase> = oracle.array("cases").map { S308EndpointCase(it as JsonValue.Object) }
    val chatCases = cases.filter { it.kind == "chat" }
    val routeCases = cases.filter { it.kind == "route" }
    val responsesCases = cases.filter { it.kind == "responses" }
    val streamFrames: List<String> = oracle.obj("stream").array("frames").map { (it as JsonValue.Text).value }
    val streamEvents: List<JsonValue.Object> = oracle.obj("stream").array("events").map { it as JsonValue.Object }
    val messages: List<ProviderMessage> = oracle.array("messages").map(::s308Message)

    fun case(id: String): S308EndpointCase = cases.single { it.id == id }

    private fun s308Message(raw: JsonValue): ProviderMessage {
        val item = raw as JsonValue.Object
        return ProviderMessage(
            role = ProviderRole.valueOf(item.text("role").uppercase(java.util.Locale.ROOT)),
            text = item.text("content"),
        )
    }
}

internal class S308EndpointCase(val fixture: JsonValue.Object) {
    val id = fixture.text("id")
    val provider = fixture.text("provider")
    val kind = fixture.text("kind")
    val auth = fixture.text("auth")
    val session: String? = (fixture["session"] as? JsonValue.Text)?.value
    val responsesEnabled = (fixture["responsesEnabled"] as? JsonValue.Bool)?.value == true
    val input = fixture.obj("input")
    val adapter = fixture.text("adapter")
    val request = fixture["request"] as? JsonValue.Object
    val url: String get() = checkNotNull(request).text("url")
    val goBase: String? = (fixture["goBase"] as? JsonValue.Text)?.value
    val protocol = fixture.text("protocol")

    fun selection(): ProviderSelection {
        val vendor = JsonValue.Object.Builder()
            .put("id", "fixture-vendor").put("providerType", provider).put("name", provider)
            .put("baseUrl", input.text("baseUrl").trimEnd('/') + "/").put("authMode", auth)
            .putIfNotNull("credentialKey", if (auth == "none") null else JsonValue.text(S308_CREDENTIAL_REFERENCE))
            .put("headers", input["headers"] as? JsonValue.Object ?: JsonValue.Object.EMPTY)
            .putIfNotNull("supportsOpenaiResponses", if (responsesEnabled) JsonValue.Bool(true) else null)
            .build()
        val raw = ProviderRawConfig(JsonValue.obj("vendor" to vendor, "profile" to profile()).literal())
        val decoded = ProviderRawConfigDecoder.decodeSelection(raw)
        return when (decoded) {
            is ProviderResult.Success -> decoded.value
            else -> error("fixture $id failed preflight: $decoded")
        }
    }

    private fun profile(): JsonValue.Object {
        val config = input.obj("config")
        val builder = JsonValue.Object.Builder()
            .put("id", "fixture-profile").put("vendorId", "fixture-vendor")
            .put("model", input.text("model")).put("label", "Local fixture")
            .put("adapter", adapter).put("providerScope", provider)
            .put("status", "active").put("enabled", true).put("isBuiltin", false)
            .put("maxTokensLimit", checkNotNull(config["maxTokensLimit"]))
            .put("parameters", parameters(config)).put("reasoning", reasoning(config))
            .put("features", features(config))
            .put(
                "capabilities",
                JsonValue.Object.Builder().put("text", true).put("streaming", true).put("tools", false)
                    .put("images", false).put("audio", false).put("video", false).build(),
            )
        if (kind != "chat") builder.put("protocol", protocol)
        return builder.build()
    }

    private fun parameters(config: JsonValue.Object): JsonValue.Object = JsonValue.Object.Builder()
        .put("maxOutputTokens", checkNotNull(config["maxOutputTokens"]))
        .put("temperature", checkNotNull(config["temperature"]))
        .putIfNotNull("topP", config["topPOverride"])
        .putIfNotNull("topK", config["topK"])
        .putIfNotNull("minP", config["minP"])
        .put("extraBody", config["extraBody"] ?: JsonValue.Object.EMPTY)
        .build()

    private fun reasoning(config: JsonValue.Object): JsonValue.Object = JsonValue.Object.Builder()
        .putIfNotNull("reasoningEffort", config["reasoningEffort"])
        .putIfNotNull("thinkingBudget", config["thinkingBudget"])
        .putIfNotNull("isReasoning", config["isReasoning"])
        .putIfNotNull("thinkingEnabled", config["thinkingEnabled"])
        .build()

    private fun features(config: JsonValue.Object): JsonValue.Object = JsonValue.Object.Builder()
        .put("reasoning", config["supportsReasoning"] ?: JsonValue.Bool(false))
        .put("embeddings", false).put("imageGeneration", false).put("reranking", false).put("favorite", false)
        .build()
}

internal class S308EndpointSession(private val case: S308EndpointCase, val peer: ProviderReplayPeer) : AutoCloseable {
    private val log = S308RecordingLogger()
    val phases = CopyOnWriteArrayList<NetworkPhase>()
    private val network = ProviderReplayNetwork(case.url, peer, OkHttpNetworkTransport(NetworkLog { phases += it.phase }))
    val requests: Int get() = network.requests
    private val credentialsRead = AtomicInteger()
    val credentialReads: Int get() = credentialsRead.get()
    private val opened = CopyOnWriteArrayList<Pair<ProviderCredential, ByteArray>>()
    private val resolver = ProviderConfigResolver()
    private val access = ProviderCallAccess(
        credentials = ProviderCredentialStore { ref ->
            assertEquals(S308_CREDENTIAL_REFERENCE, ref.key)
            credentialsRead.incrementAndGet()
            val bytes = S308_KEY.toByteArray()
            val credential = ProviderCredential.takeOwnership(bytes)
            opened += credential to bytes
            ProviderResult.Success(credential)
        },
        adapters = mainProviderAdapters(network, log, allowLoopback = false, relayVersion = "0.11.0"),
    )

    suspend fun call(
        cancellation: ProviderCancellation = ProviderCancellation { false },
        auth: String? = null,
        sessionId: String? = case.session,
        emit: suspend (ProviderStreamEvent) -> Unit = {},
    ) = withTimeout(5_000) {
        val selection = if (auth == null) case.selection() else selectionWithAuth(auth)
        val resolved = resolver.resolve(selection)
        if (resolved is ProviderResult.Success && case.kind == "chat") {
            assertEquals(case.url, resolved.value.endpoint)
            assertEquals(case.adapter, resolved.value.adapterId)
        }
        resolver.call(
            selection,
            S308EndpointFixture.messages,
            emptyList(),
            sessionId = sessionId,
            cancellation = cancellation,
            access = access,
            events = { emit(it) },
        )
    }

    private fun selectionWithAuth(auth: String): ProviderSelection {
        val vendor = JsonValue.Object.Builder()
            .put("id", "fixture-vendor").put("providerType", case.provider).put("name", case.provider)
            .put("baseUrl", case.input.text("baseUrl").trimEnd('/') + "/").put("authMode", auth)
            .put("headers", case.input["headers"] as? JsonValue.Object ?: JsonValue.Object.EMPTY).build()
        val raw = ProviderRawConfig(JsonValue.obj("vendor" to vendor, "profile" to profileForAuth()).literal())
        return ProviderRawConfigDecoder.decodeSelection(raw).let { (it as ProviderResult.Success).value }
    }

    private fun profileForAuth(): JsonValue.Object = JsonValue.Object.Builder()
        .put("id", "fixture-profile").put("vendorId", "fixture-vendor")
        .put("model", case.input.text("model")).put("label", "Local fixture")
        .put("adapter", "general").put("providerScope", case.provider)
        .put("status", "active").put("enabled", true).put("isBuiltin", false)
        .put(
            "parameters",
            JsonValue.Object.Builder().put("maxOutputTokens", 1024).put("temperature", JsonValue.Number.raw("0.4"))
                .put("extraBody", JsonValue.Object.EMPTY).build(),
        )
        .put("reasoning", JsonValue.Object.EMPTY)
        .put(
            "features",
            JsonValue.Object.Builder().put("reasoning", false).put("embeddings", false)
                .put("imageGeneration", false).put("reranking", false).put("favorite", false).build(),
        )
        .put(
            "capabilities",
            JsonValue.Object.Builder().put("text", true).put("streaming", true).put("tools", false)
                .put("images", false).put("audio", false).put("video", false).build(),
        )
        .build()

    suspend fun assertRecordedRequest(): ProviderReplayCapture {
        val received = peer.captured()
        assertEquals("POST", received.method)
        assertEquals(URI(case.url).rawPath, received.path)
        val expectedHeaders = checkNotNull(case.request).obj("headers")
        for ((name, value) in expectedHeaders.asMap()) {
            // Authorization is asserted with the fixture key below; the oracle token never leaves the artifact.
            if (name.lowercase() == "authorization") continue
            assertEquals("${case.id} header $name", (value as JsonValue.Text).value, received.headers[name.lowercase()])
        }
        if (case.auth == "bearer_api_key") {
            assertEquals("Bearer $S308_KEY", received.headers["authorization"])
        } else {
            assertFalse(received.headers.containsKey("authorization"))
        }
        assertEquals("application/json", received.headers["content-type"]?.substringBefore(';'))
        assertEquals("${case.id} complete request body", checkNotNull(case.request)["body"], JsonValue.parseObject(received.body))
        return received
    }

    fun assertNoLeakedSecrets() {
        val transcript = log.records.joinToString("\n") { it.toString() }
        val privateFields = listOf(
            S308_KEY,
            S308_CREDENTIAL_REFERENCE,
            REPLAY_PRIVATE_DETAIL,
            case.url,
            case.input.text("baseUrl"),
        )
        for (privateField in privateFields) {
            assertFalse("provider diagnostic leaked a protected field", privateField in transcript)
        }
        for ((credential, bytes) in opened) {
            assertTrue("credential survived use", runCatching { credential.use { } }.isFailure)
            assertTrue("credential bytes were not erased", bytes.all { it == 0.toByte() })
        }
    }

    override fun close() = network.close()
}

/** Model-list canary transport: rewrites only the host to the local peer, keeping path and query. */
internal class S308ListNetwork(private val origin: String, private val delegate: NetworkTransport) : NetworkTransport {
    val requests = CopyOnWriteArrayList<String>()

    override suspend fun execute(request: NetworkRequest, limits: HttpLimits): HttpResponse {
        requests += request.url
        val local = URI(origin).resolve(URI(request.url).rawPath + (URI(request.url).rawQuery?.let { "?$it" } ?: ""))
        return delegate.execute(NetworkRequest(local.toString(), request.method, request.headers, request.body), limits)
    }

    override suspend fun streamSse(
        request: NetworkRequest,
        limits: SseLimits,
        onResponse: suspend (ResponseHead) -> Unit,
        onEvent: suspend (SseEvent) -> Unit,
    ): SseEnd = delegate.streamSse(request, limits, onResponse, onEvent)

    override fun close() = delegate.close()
}

internal class S308RecordingLogger : Logger {
    val records = CopyOnWriteArrayList<LogRecord>()
    override fun log(record: LogRecord) {
        records += record
    }
}

internal fun writeS308Receipt(case: S308EndpointCase, card: String, details: JsonValue.Object) {
    val directory = System.getenv("DSTU_S308_RECEIPT_DIR")?.let(Paths::get) ?: return
    val prefix = "s308-${case.id}-$card"
    check(directory.isAbsolute && prefix.matches(Regex("[a-z0-9-]{1,100}")))
    val receipt = JsonValue.Object.Builder()
        .put("case", case.id).put("provider", case.provider).put("card", card).put("verified", true).put("details", details).build()
    Files.createDirectories(directory)
    Files.write(directory.resolve("$prefix.json"), (receipt.literal() + "\n").toByteArray(Charsets.UTF_8))
}

internal const val S308_KEY = "fixture-only-not-a-real-key"
internal const val S308_CREDENTIAL_REFERENCE = "provider.chat.s308-fixture"

internal fun ProviderResult<ProviderStreamResult>.s308Success(): ProviderStreamResult =
    (this as? ProviderResult.Success)?.value ?: error("provider stream failed: $this")

internal fun ProviderResult<ProviderStreamResult>.s308FailureCode(): ProviderFailureCode =
    (this as? ProviderResult.Failure)?.failure?.code ?: error("provider unexpectedly did not fail: $this")

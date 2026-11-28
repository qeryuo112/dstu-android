package com.deepstudent.app.runtime

import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.core.common.logging.LogRecord
import com.deepstudent.core.common.logging.Logger
import com.deepstudent.platform.network.NetworkLog
import com.deepstudent.platform.network.NetworkPhase
import com.deepstudent.platform.network.OkHttpNetworkTransport
import com.deepstudent.services.providers.api.ProviderCallAccess
import com.deepstudent.services.providers.api.ProviderCancellation
import com.deepstudent.services.providers.api.ProviderCredential
import com.deepstudent.services.providers.api.ProviderCredentialStore
import com.deepstudent.services.providers.api.ProviderMessage
import com.deepstudent.services.providers.api.ProviderRawConfig
import com.deepstudent.services.providers.api.ProviderResult
import com.deepstudent.services.providers.api.ProviderRole
import com.deepstudent.services.providers.api.ProviderSelection
import com.deepstudent.services.providers.api.ProviderStreamEvent
import com.deepstudent.services.providers.api.ProviderTool
import com.deepstudent.services.providers.backend.ProviderConfigResolver
import com.deepstudent.services.providers.backend.ProviderRawConfigDecoder
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

internal object S304ProviderFixture {
    private val fixture = JsonValue.parseObject(checkNotNull(javaClass.getResource("/s3-04-chat-oracle.json")).readText())
    val cases: List<ProviderReplayCase> = fixture.array("cases").map { ProviderReplayCase(it as JsonValue.Object) }
    val primary = cases.filter { it.stream != null }
    private val messages = fixture.array("messages").map(::providerMessage)
    private val history = fixture.array("history").map(::providerMessage)
    private val standardTools = fixture.array("tools").map(::providerTool)
    private val moonshotTools = fixture.array("moonshotTools").map(::providerTool)

    fun tools(case: ProviderReplayCase): List<ProviderTool> =
        if (case.fixture["useMoonshotTools"] == JsonValue.Bool(true)) moonshotTools else standardTools

    private fun providerTool(raw: JsonValue): ProviderTool {
        val fn = (raw as JsonValue.Object).obj("function")
        return ProviderTool(fn.text("name"), fn.text("description"), checkNotNull(fn["parameters"]).literal())
    }
    fun case(id: String): ProviderReplayCase = cases.single { it.id == id }

    fun messages(case: ProviderReplayCase): List<ProviderMessage> =
        if (case.fixture["useHistory"] == JsonValue.Bool(true)) history else messages

    private fun providerMessage(raw: JsonValue): ProviderMessage {
        val item = raw as JsonValue.Object
        val toolResult = item["tool_result"] as? JsonValue.Object
        val toolCall = item["tool_call"] as? JsonValue.Object
        return ProviderMessage(
            role = ProviderRole.valueOf(item.text("role").uppercase(java.util.Locale.ROOT)),
            text = item.text("content"),
            toolCallId = toolResult?.text("call_id"),
            toolCallsJson = toolCall?.let { JsonValue.array(listOf(callHistory(it))).literal() },
            reasoningContent = (item["thinking_content"] as? JsonValue.Text)?.value,
        )
    }

    private fun callHistory(call: JsonValue.Object) = JsonValue.Object.Builder()
        .put("id", call.text("id")).put("type", "function")
        .put(
            "function",
            JsonValue.Object.Builder().put("name", call.text("tool_name"))
                .put("arguments", checkNotNull(call["args_json"]).literal()).build(),
        ).build()
}

internal class ProviderReplayCase(val fixture: JsonValue.Object) {
    val id = fixture.text("id")
    val provider = fixture.text("provider")
    val input = fixture.obj("input")
    val request = fixture.obj("request")
    val url = request.text("url")
    val stream = fixture["stream"] as? JsonValue.Object
    val frames: List<String> get() = checkNotNull(stream).array("frames").map { (it as JsonValue.Text).value }

    fun selection(auth: String = "bearer_api_key"): ProviderSelection {
        val vendor = JsonValue.Object.Builder()
            .put("id", "fixture-vendor").put("providerType", provider).put("name", provider)
            .put("baseUrl", input.text("baseUrl")).put("authMode", auth)
            .put("credentialKey", REPLAY_CREDENTIAL_REFERENCE).put("headers", JsonValue.Object.EMPTY).build()
        val raw = ProviderRawConfig(JsonValue.obj("vendor" to vendor, "profile" to profile()).literal())
        val decoded = ProviderRawConfigDecoder.decodeSelection(raw)
        return when (decoded) {
            is ProviderResult.Success -> decoded.value
            else -> error("fixture $id failed preflight: $decoded")
        }
    }

    private fun profile(): JsonValue.Object = JsonValue.Object.Builder()
        .put("id", "fixture-profile").put("vendorId", "fixture-vendor")
        .put("model", input.text("model")).put("label", "Local fixture")
        .put("adapter", input.text("modelAdapter")).put("providerScope", provider)
        .put("status", "active").put("enabled", true).put("isBuiltin", false)
        .put("maxTokensLimit", checkNotNull(input["maxTokensLimit"]))
        .put("parameters", parameters()).put("reasoning", reasoning())
        .put("features", features()).put("capabilities", capabilities()).build()

    private fun parameters() = JsonValue.Object.Builder()
        .copy(input, "maxOutputTokens", "temperature", "topK", "minP", "extraBody")
        .putIfNotNull("topP", input["topPOverride"]).putIfNotNull("frequencyPenalty", input["frequencyPenaltyOverride"]).build()

    private fun reasoning() = JsonValue.Object.Builder()
        .copy(input, "reasoningEffort", "thinkingBudget", "includeThoughts", "thinkingEnabled", "reasoningSplit")
        .copy(input, "isReasoning", "repetitionPenalty")
        .putIfNotNull("presencePenalty", input["presencePenaltyOverride"])
        .build()

    private fun features() = JsonValue.Object.Builder()
        .put("reasoning", checkNotNull(input["supportsReasoning"]))
        .putIfNotNull("isReasoning", input["isReasoning"])
        .put("embeddings", false).put("imageGeneration", false).put("reranking", false).put("favorite", false).build()

    private fun capabilities() = JsonValue.Object.Builder()
        .put("text", true).put("streaming", true).put("tools", checkNotNull(input["supportsTools"]))
        .put("images", false).put("audio", false).put("video", false).build()
}

internal class ProviderReplaySession(private val case: ProviderReplayCase, val peer: ProviderReplayPeer) : AutoCloseable {
    private val log = ReplayLogger()
    val phases = CopyOnWriteArrayList<NetworkPhase>()
    private val network = ProviderReplayNetwork(case.url, peer, OkHttpNetworkTransport(NetworkLog { phases += it.phase }))
    val requests: Int get() = network.requests
    private val credentialsRead = AtomicInteger()
    val credentialReads: Int get() = credentialsRead.get()
    private val opened = CopyOnWriteArrayList<Pair<ProviderCredential, ByteArray>>()
    private val resolver = ProviderConfigResolver()
    private val access = ProviderCallAccess(
        credentials = ProviderCredentialStore { ref ->
            assertEquals(REPLAY_CREDENTIAL_REFERENCE, ref.key)
            credentialsRead.incrementAndGet()
            val bytes = REPLAY_KEY.toByteArray()
            val credential = ProviderCredential.takeOwnership(bytes)
            opened += credential to bytes
            ProviderResult.Success(credential)
        },
        adapters = mainProviderAdapters(network, log, allowLoopback = false),
    )

    suspend fun call(
        cancellation: ProviderCancellation = ProviderCancellation { false },
        auth: String = "bearer_api_key",
        emit: suspend (ProviderStreamEvent) -> Unit = {},
    ) = withTimeout(5_000) {
        val selection = case.selection(auth)
        val resolved = resolver.resolve(selection)
        if (resolved is ProviderResult.Success) {
            assertEquals(case.url, resolved.value.endpoint)
            assertEquals(case.fixture.text("adapter"), resolved.value.adapterId)
        }
        resolver.call(
            selection,
            S304ProviderFixture.messages(case),
            S304ProviderFixture.tools(case),
            cancellation = cancellation,
            access = access,
            events = { emit(it) },
        )
    }

    suspend fun assertRecordedRequest(): ProviderReplayCapture {
        val received = peer.captured()
        assertEquals("POST", received.method)
        assertEquals(java.net.URI(case.url).rawPath, received.path)
        assertTrue("wrong Bearer request for ${case.id}", received.headers["authorization"] == "Bearer $REPLAY_KEY")
        assertEquals("application/json", received.headers["content-type"]?.substringBefore(';'))
        assertEquals("${case.id} complete request body", case.request.obj("body"), JsonValue.parseObject(received.body))
        assertEquals(1, requests)
        assertEquals(1, credentialReads)
        return received
    }

    fun assertNoLeakedSecrets() {
        val transcript = log.records.joinToString("\n")
        val privateFields = listOf(
            REPLAY_KEY,
            REPLAY_CREDENTIAL_REFERENCE,
            REPLAY_PRIVATE_DETAIL,
            case.url,
            "协议回放",
            "思考",
            "fixture_lookup",
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

private class ReplayLogger : Logger {
    val records = CopyOnWriteArrayList<LogRecord>()
    override fun log(record: LogRecord) {
        records += record
    }
}

internal fun JsonValue.Object.text(key: String): String = (this[key] as JsonValue.Text).value
internal fun JsonValue.Object.obj(key: String): JsonValue.Object = this[key] as JsonValue.Object
internal fun JsonValue.Object.array(key: String): List<JsonValue> = (this[key] as JsonValue.Array).values

internal fun writeReplayReceipt(case: ProviderReplayCase, card: String, details: JsonValue.Object) {
    val directory = System.getenv("DSTU_S304_RECEIPT_DIR")?.let(Paths::get) ?: return
    val prefix = "s304-${case.id}-$card"
    check(directory.isAbsolute && prefix.matches(Regex("[a-z0-9-]{1,100}")))
    val receipt = JsonValue.Object.Builder()
        .put("case", case.id).put("provider", case.provider).put("card", card).put("verified", true).put("details", details).build()
    Files.createDirectories(directory)
    Files.write(directory.resolve("$prefix.json"), (receipt.literal() + "\n").toByteArray(Charsets.UTF_8))
}

private fun JsonValue.Object.Builder.copy(source: JsonValue.Object, vararg keys: String) = apply {
    keys.forEach { putIfNotNull(it, source[it]) }
}

private const val REPLAY_KEY = "fixture-only-not-a-real-key"
private const val REPLAY_CREDENTIAL_REFERENCE = "provider.chat.s304-fixture"

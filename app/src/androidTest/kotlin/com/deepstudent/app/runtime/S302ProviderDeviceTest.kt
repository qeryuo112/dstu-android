package com.deepstudent.app.runtime

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.deepstudent.services.providers.api.AuthMode
import com.deepstudent.services.providers.api.ModelAdapter
import com.deepstudent.services.providers.api.ModelParameters
import com.deepstudent.services.providers.api.ModelProfile
import com.deepstudent.services.providers.api.ModelRuntime
import com.deepstudent.services.providers.api.ProviderAdapterFactory
import com.deepstudent.services.providers.api.ProviderAdapterKey
import com.deepstudent.services.providers.api.ProviderAdapterLocator
import com.deepstudent.services.providers.api.ProviderAdapterRequest
import com.deepstudent.services.providers.api.ProviderCallAccess
import com.deepstudent.services.providers.api.ProviderCancellation
import com.deepstudent.services.providers.api.ProviderCredential
import com.deepstudent.services.providers.api.ProviderCredentialStore
import com.deepstudent.services.providers.api.ProviderEventSink
import com.deepstudent.services.providers.api.ProviderFailureCode
import com.deepstudent.services.providers.api.ProviderHeader
import com.deepstudent.services.providers.api.ProviderMessage
import com.deepstudent.services.providers.api.ProviderProtocol
import com.deepstudent.services.providers.api.ProviderProtocolSource
import com.deepstudent.services.providers.api.ProviderResult
import com.deepstudent.services.providers.api.ProviderRole
import com.deepstudent.services.providers.api.ProviderSelection
import com.deepstudent.services.providers.api.ProviderStopReason
import com.deepstudent.services.providers.api.ProviderStreamPort
import com.deepstudent.services.providers.api.ProviderStreamResult
import com.deepstudent.services.providers.api.ResolvedProviderConfig
import com.deepstudent.services.providers.api.VendorConfig
import com.deepstudent.services.providers.api.VendorConnection
import com.deepstudent.services.providers.api.VendorIdentity
import com.deepstudent.services.providers.api.VendorProtocolDefaults
import com.deepstudent.services.providers.backend.ProviderConfigResolver
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class S302ProviderDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val resolver = ProviderConfigResolver(allowLoopback = true)

    @Before
    fun requireIsolatedTarget() {
        assertEquals(ISOLATED_APPLICATION_ID, instrumentation.targetContext.packageName)
    }

    @Test
    fun frozenProvidersResolveTheirExpectedDefaultProtocolsAndRoutes() {
        val expected = listOf(
            ExpectedProvider("openai", ProviderProtocol.OPENAI_RESPONSES, "/responses"),
            ExpectedProvider("openai_codex", ProviderProtocol.OPENAI_RESPONSES, "/responses", AuthMode.OPENAI_CODEX_OAUTH),
            ExpectedProvider("deepseek", ProviderProtocol.OPENAI_RESPONSES, "/responses", model = "deepseek-flash"),
            ExpectedProvider("grok", ProviderProtocol.OPENAI_RESPONSES, "/responses"),
            ExpectedProvider("xai", ProviderProtocol.OPENAI_RESPONSES, "/responses"),
            ExpectedProvider("anthropic", ProviderProtocol.ANTHROPIC_MESSAGES, "/messages"),
            ExpectedProvider("google", ProviderProtocol.GOOGLE_GENERATE_CONTENT, ":streamGenerateContent?alt=sse"),
            ExpectedProvider("gemini", ProviderProtocol.GOOGLE_GENERATE_CONTENT, ":streamGenerateContent?alt=sse"),
            ExpectedProvider("siliconflow", ProviderProtocol.OPENAI_CHAT_COMPLETIONS, "/chat/completions"),
            ExpectedProvider("qwen", ProviderProtocol.OPENAI_CHAT_COMPLETIONS, "/chat/completions"),
            ExpectedProvider("zhipu", ProviderProtocol.OPENAI_CHAT_COMPLETIONS, "/chat/completions"),
            ExpectedProvider("doubao", ProviderProtocol.OPENAI_CHAT_COMPLETIONS, "/chat/completions"),
            ExpectedProvider("minimax", ProviderProtocol.OPENAI_CHAT_COMPLETIONS, "/chat/completions"),
            ExpectedProvider("moonshot", ProviderProtocol.OPENAI_CHAT_COMPLETIONS, "/chat/completions"),
            ExpectedProvider("nvidia", ProviderProtocol.OPENAI_CHAT_COMPLETIONS, "/chat/completions"),
            ExpectedProvider("mimo", ProviderProtocol.OPENAI_CHAT_COMPLETIONS, "/chat/completions"),
            ExpectedProvider("ernie", ProviderProtocol.OPENAI_CHAT_COMPLETIONS, "/chat/completions"),
            ExpectedProvider("mistral", ProviderProtocol.OPENAI_CHAT_COMPLETIONS, "/chat/completions"),
            ExpectedProvider("custom", ProviderProtocol.OPENAI_CHAT_COMPLETIONS, "/chat/completions"),
            ExpectedProvider("general", ProviderProtocol.OPENAI_CHAT_COMPLETIONS, "/chat/completions"),
            ExpectedProvider("opencode_go", ProviderProtocol.OPENAI_CHAT_COMPLETIONS, "/chat/completions"),
            ExpectedProvider("openrouter", ProviderProtocol.OPENAI_CHAT_COMPLETIONS, "/chat/completions"),
            ExpectedProvider("ollama", ProviderProtocol.OPENAI_CHAT_COMPLETIONS, "/chat/completions"),
        )
        assertEquals(23, expected.size)

        expected.forEach { item ->
            val resolved = success(
                resolve(
                    selection(
                        item.provider,
                        baseUrl = defaultBase(item.provider),
                        model = item.model,
                        authMode = item.authMode,
                    ),
                ),
            )
            assertEquals(item.provider, item.protocol, resolved.protocol)
            assertEquals(item.provider, ProviderProtocolSource.DEFAULT, resolved.protocolSource)
            assertTrue("${item.provider} endpoint=${resolved.endpoint}", resolved.endpoint.endsWith(item.path))
            assertEquals(item.provider, item.authMode, resolved.authMode)
        }
    }

    @Test
    fun protocolSwitchesAndOpenCodeRootRoutingAreResolvedByTheRealResolver() {
        val openAiChat = success(resolve(selection("openai", explicit = ProviderProtocol.OPENAI_CHAT_COMPLETIONS)))
        assertEquals(ProviderProtocol.OPENAI_CHAT_COMPLETIONS, openAiChat.protocol)
        assertEquals(ProviderProtocolSource.MANUAL, openAiChat.protocolSource)
        assertEquals("https://api.openai.com/v1/chat/completions", openAiChat.endpoint)

        val qwenResponses = success(resolve(selection("qwen", explicit = ProviderProtocol.OPENAI_RESPONSES)))
        assertEquals(ProviderProtocol.OPENAI_RESPONSES, qwenResponses.protocol)
        assertEquals("https://qwen.example/v1/responses", qwenResponses.endpoint)

        val minimaxAnthropic = success(
            resolve(
                selection(
                    "minimax",
                    protocol = ProviderProtocol.ANTHROPIC_MESSAGES,
                    adapter = ModelAdapter.ANTHROPIC,
                ),
            ),
        )
        assertEquals(ProviderProtocol.ANTHROPIC_MESSAGES, minimaxAnthropic.protocol)
        assertEquals("https://minimax.example/v1/messages", minimaxAnthropic.endpoint)
        assertEquals("anthropic", minimaxAnthropic.adapterId)

        val openCodeRoot = "https://opencode.ai/inference/go"
        assertEquals(
            "https://opencode.ai/inference/go/openai/v1/chat/completions",
            success(resolve(selection("opencode_go", baseUrl = openCodeRoot))).endpoint,
        )
        assertEquals(
            "https://opencode.ai/inference/go/anthropic/v1/messages",
            success(
                resolve(
                    selection(
                        "opencode_go",
                        baseUrl = openCodeRoot,
                        protocol = ProviderProtocol.ANTHROPIC_MESSAGES,
                        adapter = ModelAdapter.ANTHROPIC,
                    ),
                ),
            ).endpoint,
        )
        assertEquals(
            "https://opencode.ai/inference/go/google/v1beta/models/gemini-4-argon:streamGenerateContent?alt=sse",
            success(
                resolve(
                    selection(
                        "opencode_go",
                        baseUrl = openCodeRoot,
                        model = "gemini-4-argon",
                        protocol = ProviderProtocol.GOOGLE_GENERATE_CONTENT,
                        adapter = ModelAdapter.GOOGLE,
                    ),
                ),
            ).endpoint,
        )
    }

    @Test
    fun customProxyNativeOverridesAndDeepSeekModelGateStayDesktopCompatible() {
        val customAnthropic = success(
            resolve(selection("custom", protocol = ProviderProtocol.ANTHROPIC_MESSAGES, adapter = ModelAdapter.ANTHROPIC)),
        )
        assertEquals(ProviderProtocol.ANTHROPIC_MESSAGES, customAnthropic.protocol)
        assertEquals("https://custom.example/v1/messages", customAnthropic.endpoint)

        val customGoogle = success(
            resolve(selection("custom", protocol = ProviderProtocol.GOOGLE_GENERATE_CONTENT, adapter = ModelAdapter.GOOGLE)),
        )
        assertEquals(ProviderProtocol.GOOGLE_GENERATE_CONTENT, customGoogle.protocol)
        assertEquals("https://custom.example/v1/models/test-model:streamGenerateContent?alt=sse", customGoogle.endpoint)

        val deepseekAlias = success(resolve(selection("deepseek", model = "deepseek-reasoner")))
        assertEquals(ProviderProtocol.OPENAI_RESPONSES, deepseekAlias.protocol)
        assertEquals("https://api.deepseek.com/v1/responses", deepseekAlias.endpoint)

        val deepseekFallback = success(resolve(selection("deepseek", model = "deepseek-v9-unknown")))
        assertEquals(ProviderProtocol.OPENAI_CHAT_COMPLETIONS, deepseekFallback.protocol)
        assertEquals("https://api.deepseek.com/v1/chat/completions", deepseekFallback.endpoint)

        val rejected = failure(
            resolve(selection("deepseek", model = "deepseek-v9-unknown", explicit = ProviderProtocol.OPENAI_RESPONSES)),
        )
        assertEquals(ProviderFailureCode.UNSUPPORTED_MODEL_ROUTE, rejected)
    }

    @Test
    fun invalidConfigurationsFailBeforeAdapterCredentialAndNetworkAccess() = runBlocking {
        val peer = MockWebServer()
        try {
            peer.start()
            peer.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val adapters = AtomicInteger()
            val credentials = AtomicInteger()
            val access = ProviderCallAccess(
                credentials = ProviderCredentialStore {
                    credentials.incrementAndGet()
                    ProviderResult.Success(ProviderCredential.fromUtf8("synthetic".toCharArray()))
                },
                adapters = ProviderAdapterLocator {
                    adapters.incrementAndGet()
                    failingFactory(it)
                },
            )

            val cases = listOf(
                selection("unknown_provider", baseUrl = peer.url("/v1").toString()) to ProviderFailureCode.UNKNOWN_PROVIDER,
                selection("openai", baseUrl = "http://insecure.example/v1") to ProviderFailureCode.INSECURE_ENDPOINT,
                selection("openai", baseUrl = "https://api.openai.com/v1", headers = listOf("authorization" to "synthetic")) to
                    ProviderFailureCode.INVALID_HEADERS,
                selection("openai", baseUrl = "https://api.openai.com/v1", authMode = AuthMode.BEARER_API_KEY) to
                    ProviderFailureCode.MISSING_CREDENTIAL_REFERENCE,
                selection("openai_codex", baseUrl = "https://chatgpt.com/backend-api/codex", authMode = AuthMode.NONE) to
                    ProviderFailureCode.INVALID_AUTH_MODE,
                selection("minimax", explicit = ProviderProtocol.OPENAI_RESPONSES) to
                    ProviderFailureCode.PROTOCOL_NOT_ALLOWED_BY_DEFAULT,
                runtimeSelection("openai", ModelRuntime(enabled = false)) to ProviderFailureCode.DISABLED,
                runtimeSelection("google", ModelRuntime(), mapOf("gemini_api_version" to "v9")) to
                    ProviderFailureCode.INVALID_MODEL_PARAMETERS,
            )
            cases.forEach { (invalid, expected) ->
                val result = resolver.call(
                    invalid,
                    listOf(ProviderMessage(ProviderRole.USER, "synthetic ping")),
                    access = access,
                )
                assertEquals(expected, failure(result))
            }
            assertEquals(0, adapters.get())
            assertEquals(0, credentials.get())
            assertEquals(0, peer.requestCount)
        } finally {
            peer.shutdown()
        }
    }

    private fun resolve(selection: ProviderSelection) = resolver.resolve(selection)

    private fun selection(
        provider: String,
        baseUrl: String = defaultBase(provider),
        model: String = "test-model",
        authMode: AuthMode = defaultAuth(provider),
        protocol: ProviderProtocol? = null,
        explicit: ProviderProtocol? = null,
        adapter: ModelAdapter = ModelAdapter.GENERAL,
        headers: List<Pair<String, String>> = emptyList(),
    ): ProviderSelection = ProviderSelection(
        VendorConfig(
            identity = VendorIdentity("vendor-$provider", provider, provider),
            connection = VendorConnection(baseUrl, authMode),
            protocolDefaults = VendorProtocolDefaults(protocol),
            headers = headers.map { (name, value) -> ProviderHeader(name, value) },
        ),
        ModelProfile("model-$provider", "vendor-$provider", model, protocol = protocol, adapter = adapter),
        explicitProtocol = explicit,
    )

    private fun runtimeSelection(provider: String, runtime: ModelRuntime, extraBody: Map<String, String> = emptyMap()): ProviderSelection {
        val base = selection(provider)
        return base.copy(runtime = runtime.copy(parameters = ModelParameters(extraBody = extraBody)))
    }

    private fun defaultAuth(provider: String): AuthMode = if (provider == "openai_codex") AuthMode.OPENAI_CODEX_OAUTH else AuthMode.NONE

    private fun failure(result: ProviderResult<*>): ProviderFailureCode = (result as? ProviderResult.Failure)?.failure?.code
        ?: throw AssertionError("expected provider failure, got $result")

    private fun success(result: ProviderResult<ResolvedProviderConfig>): ResolvedProviderConfig = when (result) {
        is ProviderResult.Success -> result.value
        is ProviderResult.Failure -> throw AssertionError("${result.failure.code}: ${result.failure.message}")
        ProviderResult.Cancelled -> throw AssertionError("unexpected cancellation")
    }

    private fun failingFactory(resolved: ResolvedProviderConfig): ProviderAdapterFactory = object : ProviderAdapterFactory {
        override val key = ProviderAdapterKey(resolved.providerType, resolved.protocol, resolved.adapterId)

        override suspend fun create(resolved: ResolvedProviderConfig, credential: ProviderCredential?): ProviderStreamPort =
            object : ProviderStreamPort {
                override suspend fun stream(
                    request: ProviderAdapterRequest,
                    cancellation: ProviderCancellation,
                    events: ProviderEventSink,
                ): ProviderStreamResult = ProviderStreamResult("synthetic", null, ProviderStopReason.STOP)
            }
    }

    private fun defaultBase(provider: String): String = when (provider) {
        "openai" -> "https://api.openai.com/v1"
        "openai_codex" -> "https://chatgpt.com/backend-api/codex"
        "deepseek" -> "https://api.deepseek.com/v1"
        "anthropic" -> "https://api.anthropic.com/v1"
        "google", "gemini" -> "https://generativelanguage.googleapis.com"
        "opencode_go" -> "https://opencode.ai/inference/go/openai/v1"
        else -> "https://$provider.example/v1"
    }

    private data class ExpectedProvider(
        val provider: String,
        val protocol: ProviderProtocol,
        val path: String,
        val authMode: AuthMode = AuthMode.NONE,
        val model: String = "test-model",
    )

    private companion object {
        const val ISOLATED_APPLICATION_ID = "com.deepstudent.app.s2device"
    }
}

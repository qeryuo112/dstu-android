package com.deepstudent.services.providers.backend

import com.deepstudent.services.providers.api.AuthMode
import com.deepstudent.services.providers.api.CredentialReference
import com.deepstudent.services.providers.api.ModelAdapter
import com.deepstudent.services.providers.api.ModelProfile
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
import com.deepstudent.services.providers.api.ProviderMessage
import com.deepstudent.services.providers.api.ProviderProtocol
import com.deepstudent.services.providers.api.ProviderProtocolSource
import com.deepstudent.services.providers.api.ProviderRawConfig
import com.deepstudent.services.providers.api.ProviderResult
import com.deepstudent.services.providers.api.ProviderRole
import com.deepstudent.services.providers.api.ProviderSelection
import com.deepstudent.services.providers.api.ProviderStopReason
import com.deepstudent.services.providers.api.ProviderStreamEvent
import com.deepstudent.services.providers.api.ProviderStreamPort
import com.deepstudent.services.providers.api.ProviderStreamResult
import com.deepstudent.services.providers.api.RawCapabilities
import com.deepstudent.services.providers.api.RawFeatures
import com.deepstudent.services.providers.api.RawGenerationParameters
import com.deepstudent.services.providers.api.RawModelIdentity
import com.deepstudent.services.providers.api.RawModelLimits
import com.deepstudent.services.providers.api.RawModelProfile
import com.deepstudent.services.providers.api.RawModelState
import com.deepstudent.services.providers.api.RawReasoningFlags
import com.deepstudent.services.providers.api.RawSamplingParameters
import com.deepstudent.services.providers.api.RawSamplingValues
import com.deepstudent.services.providers.api.RawVendorConfig
import com.deepstudent.services.providers.api.RawVendorIdentity
import com.deepstudent.services.providers.api.ResolvedProviderConfig
import com.deepstudent.services.providers.api.VendorConfig
import com.deepstudent.services.providers.api.VendorConnection
import com.deepstudent.services.providers.api.VendorIdentity
import com.deepstudent.services.providers.api.VendorProtocolDefaults
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderConfigTest {
    private val resolver = ProviderConfigResolver()
    private val noneCredentials = ProviderCredentialStore { error("credential lookup is forbidden in preflight") }

    @Test
    fun profileProtocolOverridesVendorProtocol() {
        val resolved = success(
            resolver.resolve(
                buildSelection(
                    "openai",
                    vendorProtocol = ProviderProtocol.OPENAI_RESPONSES,
                    profileProtocol = ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
                ),
            ),
        )
        assertEquals(ProviderProtocol.OPENAI_CHAT_COMPLETIONS, resolved.protocol)
        assertTrue(resolved.endpoint.endsWith("/chat/completions"))
    }

    @Test
    fun explicitNativeOverrideWorksOnlyForSupportedCustomHosts() {
        val anthropic = success(
            resolver.resolve(
                buildSelection("custom", adapter = ModelAdapter.ANTHROPIC, explicit = ProviderProtocol.ANTHROPIC_MESSAGES),
            ),
        )
        assertTrue(anthropic.endpoint.endsWith("/messages"))
        assertEquals("anthropic", anthropic.adapterId)
        val google = success(
            resolver.resolve(
                buildSelection("custom", adapter = ModelAdapter.GOOGLE, explicit = ProviderProtocol.GOOGLE_GENERATE_CONTENT),
            ),
        )
        assertTrue(google.endpoint.contains(":streamGenerateContent?alt=sse"))
        assertEquals("google", google.adapterId)
    }

    @Test
    fun minimaxAnthropicAndOpenCodeNativeDeclarationsResolve() {
        val mini = success(
            resolver.resolve(
                buildSelection("minimax", profileProtocol = ProviderProtocol.ANTHROPIC_MESSAGES, adapter = ModelAdapter.ANTHROPIC),
            ),
        )
        assertEquals(ProviderProtocol.ANTHROPIC_MESSAGES, mini.protocol)
        val openCode = success(
            resolver.resolve(
                buildSelection(
                    "opencode_go",
                    profileProtocol = ProviderProtocol.ANTHROPIC_MESSAGES,
                    adapter = ModelAdapter.ANTHROPIC,
                    baseUrl = "https://opencode.ai/inference/go/openai/v1",
                ),
            ),
        )
        assertEquals(ProviderProtocol.ANTHROPIC_MESSAGES, openCode.protocol)
        assertTrue(openCode.endpoint.startsWith("https://opencode.ai/inference/go/anthropic/v1"))
        assertEquals("https://opencode.ai/inference/go/anthropic/v1/messages", openCode.endpoint)
    }

    @Test
    fun codexOAuthHasNoApiKeyReference() {
        val resolved = success(
            resolver.resolve(
                buildSelection(
                    "openai_codex",
                    authMode = AuthMode.OPENAI_CODEX_OAUTH,
                    baseUrl = "https://chatgpt.com/backend-api/codex",
                ),
            ),
        )
        assertEquals(AuthMode.OPENAI_CODEX_OAUTH, resolved.authMode)
        assertEquals(null, resolved.credential)
    }

    @Test
    fun deepseekOfficialModelGatingRejectsUnlistedResponseModelButUsesChat() {
        val rejected = failure(
            resolver.resolve(
                buildSelection(
                    "deepseek",
                    profileProtocol = ProviderProtocol.OPENAI_RESPONSES,
                    model = "deepseek-v9-unknown",
                    baseUrl = "https://api.deepseek.com/v1",
                ),
            ),
        )
        assertEquals(ProviderFailureCode.UNSUPPORTED_MODEL_ROUTE, rejected)
        val defaulted = success(
            resolver.resolve(
                buildSelection("deepseek", model = "deepseek-v9-unknown", baseUrl = "https://api.deepseek.com/v1"),
            ),
        )
        assertEquals(ProviderProtocol.OPENAI_CHAT_COMPLETIONS, defaulted.protocol)
    }

    @Test
    fun invalidConfigurationsFailBeforeAdapterAndCredentialLookup() = runTest {
        var adapterCalls = 0
        var credentialCalls = 0
        val locator = ProviderAdapterLocator {
            adapterCalls++
            error("must not locate")
        }
        val credentials = ProviderCredentialStore {
            credentialCalls++
            error("must not read")
        }
        val invalid = buildSelection("openai", baseUrl = "http://insecure.example/v1", authMode = AuthMode.BEARER_API_KEY)
        val failure = resolver.call(
            invalid,
            listOf(ProviderMessage(ProviderRole.USER, "ping")),
            access = ProviderCallAccess(credentials, locator),
        )
        assertTrue(failure is ProviderResult.Failure)
        assertEquals(0, adapterCalls)
        assertEquals(0, credentialCalls)
    }

    @Test
    fun validCallReadsCredentialOnceAndStreamsEventsIncrementally() = runTest {
        var credentialReads = 0
        var factoryCalls = 0
        val received = mutableListOf<ProviderStreamEvent>()
        val credentials = ProviderCredentialStore { reference ->
            credentialReads++
            assertEquals("provider-test-key", reference.key)
            ProviderResult.Success(ProviderCredential.fromUtf8("secret".toCharArray()))
        }
        val locator = ProviderAdapterLocator { resolved ->
            factoryCalls++
            object : ProviderAdapterFactory {
                override val key = ProviderAdapterKey(resolved.providerType, resolved.protocol, resolved.adapterId)
                override suspend fun create(resolved: ResolvedProviderConfig, credential: ProviderCredential?): ProviderStreamPort {
                    check(credential != null)
                    credential.use { bytes -> assertEquals("secret", bytes.decodeToString()) }
                    return object : ProviderStreamPort {
                        override suspend fun stream(
                            request: ProviderAdapterRequest,
                            cancellation: ProviderCancellation,
                            events: ProviderEventSink,
                        ): ProviderStreamResult {
                            events.emit(ProviderStreamEvent.TextDelta("a"))
                            events.emit(ProviderStreamEvent.TextDelta("b"))
                            events.emit(ProviderStreamEvent.Completed(ProviderStopReason.STOP))
                            return ProviderStreamResult("ab", null, ProviderStopReason.STOP)
                        }
                    }
                }
            }
        }
        val result = resolver.call(
            buildSelection("openai", authMode = AuthMode.BEARER_API_KEY, credential = CredentialReference("provider-test-key")),
            listOf(ProviderMessage(ProviderRole.USER, "ping")),
            access = ProviderCallAccess(credentials, locator),
            events = ProviderEventSink { received += it },
        )
        assertEquals(ProviderStreamResult("ab", null, ProviderStopReason.STOP), success(result))
        assertEquals(1, credentialReads)
        assertEquals(1, factoryCalls)
        assertEquals(3, received.size)
        assertTrue(received[0] is ProviderStreamEvent.TextDelta)
    }

    @Test
    fun malformedRawInputsReturnTypedFailures() {
        assertEquals(
            ProviderFailureCode.INVALID_CONFIGURATION,
            failure(ProviderRawConfigDecoder.decodeSelection(ProviderRawConfig("not-json"))),
        )

        val badProvider = rawVendor(providerType = "not-a-provider", protocol = null)
        assertEquals(ProviderFailureCode.UNKNOWN_PROVIDER, failure(ProviderRawConfigDecoder.decodeVendor(badProvider)))

        val badProtocol = rawVendor(protocol = "bogus")
        assertEquals(ProviderFailureCode.UNSUPPORTED_PROTOCOL, failure(ProviderRawConfigDecoder.decodeVendor(badProtocol)))

        val badEndpoint = rawVendor(baseUrl = "http://example.test/v1", protocol = null)
        assertEquals(ProviderFailureCode.INVALID_ENDPOINT, failure(ProviderRawConfigDecoder.decodeVendor(badEndpoint)))

        val badHeaders = rawVendor(headers = "{\"bad\":12}", protocol = null)
        assertEquals(ProviderFailureCode.INVALID_HEADERS, failure(ProviderRawConfigDecoder.decodeVendor(badHeaders)))

        val badTemperature = rawProfile(temperature = "3.0")
        assertEquals(
            ProviderFailureCode.INVALID_MODEL_PARAMETERS,
            failure(ProviderRawConfigDecoder.decodeProfile(badTemperature)),
        )
    }

    @Test
    fun jsonConfigurationKeepsNumericParametersAndModelCapabilityOverrides() {
        val decoded = ProviderRawConfigDecoder.decode(
            ProviderRawConfig(
                """
                {
                  "vendor": {
                    "id":"vendor", "providerType":"custom", "baseUrl":"https://provider.example/v1",
                    "name":"Example", "authMode":"none", "headers":{"x-fixture":"accepted"}
                  },
                  "profile": {
                    "id":"model", "vendorId":"vendor", "model":"fixture", "label":"Fixture",
                    "adapter":"general", "parameters":{
                      "maxOutputTokens":2048, "temperature":0.2, "topP":0.9, "topK":32,
                      "extraBody":{"fixture_flag":true,"fixture_text":"ok"}
                    },
                    "reasoning":{"includeThoughts":true,"thinkingBudget":128,"isReasoning":true,
                      "thinkingEnabled":true,"reasoningSplit":true},
                    "capabilities":{
                      "text":true,"streaming":true,"tools":true,"images":false,"audio":false,"video":false
                    },
                    "features":{
                      "reasoning":true,"embeddings":false,"imageGeneration":false,"reranking":false,"favorite":false
                    },
                    "maxTokensLimit":4096, "contextWindow":8192, "enabled":true, "status":"active", "isBuiltin":false
                  }
                }
                """.trimIndent(),
            ),
        )
        val parsed = (decoded as ProviderResult.Success).value
        assertEquals(0.2, parsed.runtime.parameters.temperature, 0.0)
        assertEquals(32, parsed.runtime.parameters.topK)
        assertEquals("true", parsed.runtime.parameters.extraBody["fixture_flag"])
        assertEquals("ok", parsed.runtime.parameters.extraBody["fixture_text"])
        assertEquals(8192, parsed.runtime.contextWindow)
        assertTrue(parsed.runtime.capabilities.tools)
        assertTrue(parsed.runtime.isReasoning)
        assertTrue(parsed.runtime.thinkingEnabled)
        assertEquals(true, parsed.runtime.reasoning.reasoningSplit)
        val route = success(resolver.resolve(ProviderSelection(parsed.vendor, parsed.profile, parsed.runtime)))
        assertEquals("https://provider.example/v1/chat/completions", route.endpoint)
        assertEquals("accepted", route.headers.single().value)
    }

    private fun rawVendor(
        providerType: String = "openai",
        baseUrl: String = "https://api.openai.com/v1",
        protocol: String? = "openai_chat_completions",
        headers: String = "{}",
    ): RawVendorConfig = RawVendorConfig(
        identity = RawVendorIdentity("vendor-raw", providerType, "Raw"),
        baseUrl = baseUrl,
        authMode = "none",
        protocol = protocol,
        supportsOpenaiResponses = null,
        credentialKey = null,
        headersJson = headers,
    )

    private fun rawProfile(temperature: String = "0.7"): RawModelProfile = RawModelProfile(
        identity = RawModelIdentity("model-raw", "vendor-raw", "gpt-5", "Raw", null),
        protocol = null,
        adapter = "general",
        generation = RawGenerationParameters(
            maxOutputTokens = 8192,
            temperature = temperature,
            reasoningEffort = null,
            thinkingBudget = null,
            includeThoughts = false,
            reasoningMode = null,
            extraBodyJson = "{}",
            reasoningFlags = RawReasoningFlags(false, true),
        ),
        sampling = RawSamplingParameters(
            values = RawSamplingValues(null, null, null, null, null, null),
            limits = RawModelLimits(null, null),
            state = RawModelState(
                enabled = true,
                status = "active",
                isBuiltin = false,
                capabilities = RawCapabilities(true, true, false, false, false, false),
                features = RawFeatures(false, false, false, false, false),
            ),
        ),
    )

    private fun buildSelection(
        providerType: String,
        model: String = "test-model",
        baseUrl: String = defaultBase(providerType),
        authMode: AuthMode? = AuthMode.NONE,
        vendorProtocol: ProviderProtocol? = null,
        profileProtocol: ProviderProtocol? = null,
        adapter: ModelAdapter = ModelAdapter.GENERAL,
        explicit: ProviderProtocol? = null,
        credential: CredentialReference? = null,
    ): ProviderSelection {
        val vendor = VendorConfig(
            identity = VendorIdentity("vendor-$providerType", providerType, providerType),
            connection = VendorConnection(baseUrl, authMode, credential),
            protocolDefaults = VendorProtocolDefaults(vendorProtocol),
        )
        val profile = ModelProfile("model-$providerType", vendor.id, model, protocol = profileProtocol, adapter = adapter)
        return ProviderSelection(vendor, profile, explicitProtocol = explicit)
    }

    private fun authFor(providerType: String): AuthMode? = if (providerType == "openai_codex") {
        AuthMode.OPENAI_CODEX_OAUTH
    } else {
        AuthMode.NONE
    }

    private fun defaultBase(providerType: String): String = when (providerType) {
        "openai" -> "https://api.openai.com/v1"
        "openai_codex" -> "https://chatgpt.com/backend-api/codex"
        "deepseek" -> "https://api.deepseek.com/v1"
        "anthropic" -> "https://api.anthropic.com/v1"
        "google" -> "https://generativelanguage.googleapis.com"
        "gemini" -> "https://generativelanguage.googleapis.com"
        "opencode_go" -> "https://opencode.ai/inference/go/openai/v1"
        else -> "https://$providerType.example/v1"
    }

    private fun success(result: ProviderResult<ResolvedProviderConfig>, provider: String = ""): ResolvedProviderConfig = when (result) {
        is ProviderResult.Success -> result.value
        is ProviderResult.Failure -> throw AssertionError("$provider: ${result.failure.code}")
        ProviderResult.Cancelled -> throw AssertionError("$provider: cancelled")
    }
    private fun success(result: ProviderResult<ProviderStreamResult>): ProviderStreamResult = (result as ProviderResult.Success).value
    private fun failure(result: ProviderResult<*>): ProviderFailureCode = (result as ProviderResult.Failure).failure.code
}

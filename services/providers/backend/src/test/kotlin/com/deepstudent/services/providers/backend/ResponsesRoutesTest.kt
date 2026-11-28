package com.deepstudent.services.providers.backend

import com.deepstudent.services.providers.api.AuthMode
import com.deepstudent.services.providers.api.ModelAdapter
import com.deepstudent.services.providers.api.ModelProfile
import com.deepstudent.services.providers.api.OpenAiResponsesRoutes
import com.deepstudent.services.providers.api.ProviderFailure
import com.deepstudent.services.providers.api.ProviderFailureCode
import com.deepstudent.services.providers.api.ProviderProtocol
import com.deepstudent.services.providers.api.ProviderResult
import com.deepstudent.services.providers.api.ProviderSelection
import com.deepstudent.services.providers.api.VendorConfig
import com.deepstudent.services.providers.api.VendorConnection
import com.deepstudent.services.providers.api.VendorIdentity
import org.junit.Assert.assertEquals
import org.junit.Test

class ResponsesRoutesTest {
    private val resolver = ProviderConfigResolver()

    @Test
    fun officialRouteSuppliesDefaultAdapterAndEndpointWithoutModelOverrides() {
        val routes = listOf(
            Triple("openai", "general", "https://api.openai.com/v1/responses"),
            Triple("deepseek", "deepseek", "https://api.deepseek.com/v1/responses"),
            Triple("grok", "grok", "https://api.x.ai/v1/responses"),
            Triple("xai", "grok", "https://api.x.ai/v1/responses"),
        )
        for ((provider, id, endpoint) in routes) {
            val resolved = (resolver.resolve(route(provider)) as ProviderResult.Success).value
            assertEquals(ProviderProtocol.OPENAI_RESPONSES, resolved.protocol)
            assertEquals(id, resolved.adapterId)
            assertEquals(endpoint, resolved.endpoint)
        }
    }

    @Test
    fun codexNeverUsesCustomEndpointOrCredentialMode() {
        val target = route("openai_codex", "https://not-codex.invalid/token", AuthMode.OPENAI_CODEX_OAUTH)
        val resolved = (resolver.resolve(target) as ProviderResult.Success).value
        assertEquals(OpenAiResponsesRoutes.CODEX_ENDPOINT, resolved.endpoint)
        assertEquals("general", resolved.adapterId)
        assertInvalidAuth(resolver.resolve(route("openai_codex")))
        assertInvalidAuth(resolver.resolve(route("grok", auth = AuthMode.X_API_KEY)))
        assertInvalidAuth(resolver.resolve(route("openai", auth = AuthMode.OPENAI_CODEX_OAUTH)))
    }

    private fun route(provider: String, base: String = "", auth: AuthMode = AuthMode.NONE): ProviderSelection {
        val vendor = VendorConfig(VendorIdentity(provider, provider), VendorConnection(base, auth))
        val model = if (provider == "deepseek") "deepseek-flash" else "synthetic"
        return ProviderSelection(vendor, ModelProfile("model", vendor.id, model, adapter = ModelAdapter.GENERAL))
    }

    private fun assertInvalidAuth(result: ProviderResult<*>) {
        assertEquals(ProviderResult.Failure(ProviderFailure(ProviderFailureCode.INVALID_AUTH_MODE)), result)
    }
}

package com.deepstudent.services.providers.backend

import com.deepstudent.services.providers.api.AuthMode
import com.deepstudent.services.providers.api.ModelProfile
import com.deepstudent.services.providers.api.ProviderFailureCode
import com.deepstudent.services.providers.api.ProviderResult
import com.deepstudent.services.providers.api.ProviderSelection
import com.deepstudent.services.providers.api.VendorConfig
import com.deepstudent.services.providers.api.VendorConnection
import com.deepstudent.services.providers.api.VendorIdentity
import org.junit.Assert.assertEquals
import org.junit.Test

class ProviderEndpointReviewTest {
    @Test fun fullEndpointRetainsAuthorityAndExactlyOneProtocolSuffix() {
        val config = ProviderConfigResolver().resolve(selection("https://API.example:8443/a/v1/chat/completions"))
        val route = (config as ProviderResult.Success).value
        assertEquals("https://api.example:8443/a/v1/chat/completions", route.endpoint)
    }

    @Test fun cleartextRequiresExplicitLoopbackPolicyAndExactLocalHost() {
        val local = selection("http://127.0.0.1:28914/v1")
        val result = ProviderConfigResolver().resolve(local) as ProviderResult.Failure
        assertEquals(ProviderFailureCode.INSECURE_ENDPOINT, result.failure.code)
        val debug = ProviderConfigResolver(allowLoopback = true)
        val resolved = (debug.resolve(local) as ProviderResult.Success).value
        assertEquals("http://127.0.0.1:28914/v1/chat/completions", resolved.endpoint)
        val outside = debug.resolve(selection("http://127.0.0.1.example:28914/v1")) as ProviderResult.Failure
        assertEquals(ProviderFailureCode.INSECURE_ENDPOINT, outside.failure.code)
    }

    @Test fun unconfiguredKeyIsRejectedUnlessNoAuthWasDeclared() {
        val vendor = VendorConfig(VendorIdentity("fixture", "custom"), VendorConnection("https://provider.example"))
        val result = ProviderConfigResolver().resolve(ProviderSelection(vendor, ModelProfile("model", vendor.id, "chat")))
        assertEquals(ProviderFailureCode.MISSING_CREDENTIAL_REFERENCE, (result as ProviderResult.Failure).failure.code)
    }

    private fun selection(url: String): ProviderSelection {
        val vendor = VendorConfig(VendorIdentity("fixture", "custom"), VendorConnection(url, AuthMode.NONE))
        return ProviderSelection(vendor, ModelProfile("model", vendor.id, "chat"))
    }
}

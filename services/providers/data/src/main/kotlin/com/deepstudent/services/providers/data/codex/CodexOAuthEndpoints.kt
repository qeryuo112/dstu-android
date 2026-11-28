package com.deepstudent.services.providers.data.codex

import com.deepstudent.services.providers.api.OpenAiResponsesRoutes
import java.net.URI
import java.util.Locale

internal data class CodexDeviceEndpoints(
    val userCodeUrl: String,
    val tokenUrl: String,
    val verificationUrl: String,
    val redirectUrl: String,
)

internal data class CodexOAuthEndpoints(
    val clientId: String,
    val authorizeUrl: String,
    val tokenUrl: String,
    val revokeUrl: String,
    val device: CodexDeviceEndpoints,
    val responsesUrl: String,
    val usageUrl: String,
) {
    val deviceUserCodeUrl: String get() = device.userCodeUrl
    val deviceTokenUrl: String get() = device.tokenUrl
    val deviceVerificationUrl: String get() = device.verificationUrl
    val deviceRedirectUrl: String get() = device.redirectUrl

    init {
        require(clientId.matches(CLIENT_ID_PATTERN)) { "invalid OAuth client id" }
        listOf(authorizeUrl, tokenUrl, revokeUrl, responsesUrl, usageUrl).forEach(::requireHttp)
        listOf(device.userCodeUrl, device.tokenUrl, device.verificationUrl, device.redirectUrl).forEach(::requireHttp)
    }

    companion object {
        const val CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
        const val ISSUER = "https://auth.openai.com"
        const val DEVICE_VERIFICATION_URL = "https://auth.openai.com/codex/device"
        const val USAGE_ENDPOINT = "https://chatgpt.com/backend-api/wham/usage"
        private val CLIENT_ID_PATTERN = Regex("[A-Za-z0-9_-]{1,128}")

        val PRODUCTION = CodexOAuthEndpoints(
            clientId = CLIENT_ID,
            authorizeUrl = "$ISSUER/oauth/authorize",
            tokenUrl = "$ISSUER/oauth/token",
            revokeUrl = "$ISSUER/oauth/revoke",
            device = CodexDeviceEndpoints(
                userCodeUrl = "$ISSUER/api/accounts/deviceauth/usercode",
                tokenUrl = "$ISSUER/api/accounts/deviceauth/token",
                verificationUrl = DEVICE_VERIFICATION_URL,
                redirectUrl = "$ISSUER/deviceauth/callback",
            ),
            responsesUrl = OpenAiResponsesRoutes.CODEX_ENDPOINT,
            usageUrl = USAGE_ENDPOINT,
        )

        fun forTest(baseUrl: String): CodexOAuthEndpoints {
            val base = baseUrl.trimEnd('/')
            return CodexOAuthEndpoints(
                clientId = "test-client",
                authorizeUrl = "$base/oauth/authorize",
                tokenUrl = "$base/oauth/token",
                revokeUrl = "$base/oauth/revoke",
                device = CodexDeviceEndpoints(
                    userCodeUrl = "$base/device/usercode",
                    tokenUrl = "$base/device/token",
                    verificationUrl = "$base/device",
                    redirectUrl = "$base/device/callback",
                ),
                responsesUrl = "$base/codex/responses",
                usageUrl = "$base/wham/usage",
            )
        }

        private fun requireHttp(value: String) {
            val uri = try {
                URI(value)
            } catch (_: Exception) {
                throw IllegalArgumentException("invalid OAuth endpoint")
            }
            require(uri.scheme?.lowercase(Locale.ROOT) in setOf("http", "https") && uri.host != null) {
                "invalid OAuth endpoint"
            }
            require(uri.userInfo == null && uri.fragment == null) { "invalid OAuth endpoint" }
        }
    }
}

package com.deepstudent.services.providers.data.codex

import com.deepstudent.core.common.id.Digests
import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.platform.network.HttpLimits
import com.deepstudent.platform.network.NetworkError
import com.deepstudent.platform.network.NetworkException
import com.deepstudent.platform.network.NetworkHeader
import com.deepstudent.platform.network.NetworkRequest
import com.deepstudent.platform.network.NetworkTransport
import com.deepstudent.services.providers.api.ProviderCancellation
import com.deepstudent.services.providers.api.ProviderException
import com.deepstudent.services.providers.api.ProviderFailure
import com.deepstudent.services.providers.api.ProviderFailureCode
import com.deepstudent.services.providers.api.ProviderResult
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException

internal data class CodexPkce(val verifier: String, val challenge: String)

internal object CodexAuthProtocol {
    private const val SCOPE = "openid profile email offline_access"
    private const val ORIGINATOR = "codex_cli_rs"
    private const val MAX_BODY = 512 * 1024
    private val STATE = Regex("[A-Za-z0-9_-]{32,256}")
    private val CODE = Regex("[A-Za-z0-9._~-]{1,4096}")

    fun generatePkce(random: SecureRandom): CodexPkce {
        val bytes = ByteArray(64).also(random::nextBytes)
        val verifier = base64Url(bytes)
        val challenge = base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
        return CodexPkce(verifier, challenge)
    }

    fun generateState(random: SecureRandom): String = base64Url(ByteArray(32).also(random::nextBytes))

    fun buildAuthorizationUrl(endpoints: CodexOAuthEndpoints, redirectUri: String, challenge: String, state: String): String {
        require(redirectUri.startsWith("http://localhost:")) { "invalid redirect URI" }
        return buildQuery(
            endpoints.authorizeUrl,
            linkedMapOf(
                "response_type" to "code",
                "client_id" to endpoints.clientId,
                "redirect_uri" to redirectUri,
                "scope" to SCOPE,
                "code_challenge" to challenge,
                "code_challenge_method" to "S256",
                "id_token_add_organizations" to "true",
                "codex_cli_simplified_flow" to "true",
                "state" to state,
                "originator" to ORIGINATOR,
            ),
        )
    }

    fun callback(uri: String, expectedRedirect: String, expectedState: String): CodexCallback {
        val parsed = parseCallbackUri(uri) ?: return CodexCallback.Invalid
        if (!isCallbackTarget(parsed, expectedRedirect)) return CodexCallback.Invalid
        val params = parseQuery(parsed.rawQuery)
        val state = singleQueryValue(params, STATE_PARAM) ?: return CodexCallback.Invalid
        if (!STATE.matches(state) || !constantTimeEquals(state, expectedState)) return CodexCallback.Invalid
        singleQueryValue(params, ERROR_PARAM)?.let { return CodexCallback.Denied(it.take(MAX_ERROR_LENGTH)) }
        val code = singleQueryValue(params, CODE_PARAM) ?: return CodexCallback.Invalid
        return if (CODE.matches(code)) CodexCallback.Code(code) else CodexCallback.Invalid
    }

    private fun parseCallbackUri(uri: String): URI? = try {
        URI(uri)
    } catch (_: Exception) {
        null
    }

    /** The callback must be the exact loopback redirect this attempt issued. */
    private fun isCallbackTarget(parsed: URI, expectedRedirect: String): Boolean =
        parsed.scheme == "http" && parsed.host == "localhost" && parsed.userInfo == null && parsed.fragment == null &&
            "${parsed.scheme}://${parsed.host}:${parsed.port}${parsed.path}" == expectedRedirect

    private fun singleQueryValue(params: Map<String, List<String>>, key: String): String? = params[key]?.singleOrNull()

    fun tokenRequest(
        endpoints: CodexOAuthEndpoints,
        fields: Map<String, String>,
        stage: CodexAuthStage,
        cancellation: ProviderCancellation,
    ): NetworkRequest = NetworkRequest(
        url = endpoints.tokenUrl,
        method = "POST",
        headers = listOf(
            NetworkHeader("content-type", "application/x-www-form-urlencoded"),
            NetworkHeader("accept", "application/json"),
            NetworkHeader("user-agent", USER_AGENT),
        ),
        body = encodeForm(fields),
    ).also { cancellation.check() }

    fun deviceCodeRequest(endpoints: CodexOAuthEndpoints, cancellation: ProviderCancellation): NetworkRequest = NetworkRequest(
        url = endpoints.deviceUserCodeUrl,
        method = "POST",
        headers = listOf(
            NetworkHeader("content-type", "application/json"),
            NetworkHeader("accept", "application/json"),
            NetworkHeader("user-agent", USER_AGENT),
        ),
        body = """{"client_id":"${jsonEscape(endpoints.clientId)}"}""".toByteArray(Charsets.UTF_8),
    ).also { cancellation.check() }

    fun deviceTokenRequest(
        endpoints: CodexOAuthEndpoints,
        deviceAuthId: String,
        userCode: String,
        cancellation: ProviderCancellation,
    ): NetworkRequest = NetworkRequest(
        url = endpoints.deviceTokenUrl,
        method = "POST",
        headers = listOf(
            NetworkHeader("content-type", "application/json"),
            NetworkHeader("accept", "application/json"),
            NetworkHeader("user-agent", USER_AGENT),
        ),
        body = """{"device_auth_id":"${jsonEscape(deviceAuthId)}","user_code":"${jsonEscape(userCode)}"}"""
            .toByteArray(Charsets.UTF_8),
    ).also { cancellation.check() }

    fun revokeRequest(endpoints: CodexOAuthEndpoints, refreshToken: String): NetworkRequest = NetworkRequest(
        url = endpoints.revokeUrl,
        method = "POST",
        headers = listOf(
            NetworkHeader("content-type", "application/json"),
            NetworkHeader("accept", "application/json"),
        ),
        body = """{"token":"${jsonEscape(
            refreshToken,
        )}","token_type_hint":"refresh_token","client_id":"${jsonEscape(endpoints.clientId)}"}"""
            .toByteArray(Charsets.UTF_8),
    )

    suspend fun executeJson(transport: NetworkTransport, request: NetworkRequest, cancellation: ProviderCancellation): CodexHttpResult {
        cancellation.check()
        val response = try {
            transport.execute(request, HttpLimits(MAX_BODY))
        } catch (failure: NetworkException) {
            currentCoroutineContext().ensureActive()
            return CodexHttpResult.Network(failure.error)
        }
        cancellation.check()
        val body = response.body
        return CodexHttpResult.Response(
            status = response.head.statusCode,
            content = decodeJson(body),
        )
    }

    fun decodeJson(bytes: ByteArray): JsonValue? {
        if (bytes.isEmpty()) return null
        return try {
            JsonValue.parse(bytes.decodeToString())
        } catch (_: Exception) {
            null
        }
    }

    fun decodeToken(value: JsonValue?): CodexTokenResponse {
        val root =
            value as? JsonValue.Object ?: throw CodexWireFailure(CodexAuthFailureCode.MALFORMED_RESPONSE, CodexAuthStage.TOKEN_EXCHANGE)
        val access = root.requiredText("access_token") ?: throw CodexWireFailure(
            CodexAuthFailureCode.MALFORMED_RESPONSE,
            CodexAuthStage.TOKEN_EXCHANGE,
        )
        val refresh = root.optionalText("refresh_token")
        val id = root.optionalText("id_token")
        val expires = root.optionalLong("expires_in")
        return CodexTokenResponse(access, refresh, id, expires)
    }

    fun accountId(token: String?): String? {
        val claims = token?.let(::parseJwtClaims) ?: return null
        val auth = claims["https://api.openai.com/auth"] as? JsonValue.Object
        val organizations = claims["organizations"] as? JsonValue.Array
        return sequenceOf(
            (claims["chatgpt_account_id"] as? JsonValue.Text)?.value,
            (auth?.get("chatgpt_account_id") as? JsonValue.Text)?.value,
            ((organizations?.values?.firstOrNull() as? JsonValue.Object)?.get("id") as? JsonValue.Text)?.value,
        ).mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }.firstOrNull()
    }

    fun parseJwtClaims(token: String): JsonValue.Object? {
        val pieces = token.split('.')
        if (pieces.size != 3 || pieces[1].isEmpty() || pieces[1].length % 4 == 1) return null
        if (!pieces[1].matches(Regex("[A-Za-z0-9_-]+"))) return null
        val decoded = try {
            Base64.getUrlDecoder().decode(pieces[1])
        } catch (_: IllegalArgumentException) {
            return null
        }
        val root = try {
            JsonValue.parse(decoded.decodeToString())
        } catch (_: Exception) {
            return null
        }
        return root as? JsonValue.Object
    }

    fun accountHint(accountId: String): String = "***" + accountId.takeLast(6)

    fun constantTimeEquals(left: String, right: String): Boolean =
        MessageDigest.isEqual(left.toByteArray(Charsets.UTF_8), right.toByteArray(Charsets.UTF_8))

    private fun JsonValue.Object.requiredText(key: String): String? {
        val value = this[key] as? JsonValue.Text ?: return null
        return value.value.trim().takeIf(String::isNotEmpty)
    }

    private fun JsonValue.Object.optionalText(key: String): String? = when (val value = this[key]) {
        null, JsonValue.Null -> null
        is JsonValue.Text -> value.value
        else -> null
    }

    private fun JsonValue.Object.optionalLong(key: String): Long? = when (val value = this[key]) {
        null, JsonValue.Null -> null
        is JsonValue.Number -> value.asLongOrNull()
        else -> null
    }

    private fun buildQuery(base: String, fields: Map<String, String>): String {
        val separator = if (base.contains('?')) '&' else '?'
        return base + separator + fields.entries.joinToString("&") { (key, value) ->
            encode(key) + "=" + encode(value)
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")

    private fun encodeForm(fields: Map<String, String>): ByteArray =
        fields.entries.joinToString("&") { (key, value) -> encode(key) + "=" + encode(value) }
            .toByteArray(Charsets.UTF_8)

    private fun parseQuery(raw: String?): Map<String, List<String>> {
        if (raw.isNullOrEmpty()) return emptyMap()
        val values = linkedMapOf<String, MutableList<String>>()
        raw.split('&').forEach { part ->
            if (part.isEmpty()) return@forEach
            val split = part.indexOf('=')
            val key = if (split < 0) part else part.substring(0, split)
            val value = if (split < 0) "" else part.substring(split + 1)
            val decodedKey = try {
                URLDecoder.decode(key, Charsets.UTF_8)
            } catch (_: IllegalArgumentException) {
                return emptyMap()
            }
            val decodedValue = try {
                URLDecoder.decode(value, Charsets.UTF_8)
            } catch (_: IllegalArgumentException) {
                return emptyMap()
            }
            values.getOrPut(decodedKey) { mutableListOf() }.add(decodedValue)
        }
        return values
    }

    private fun base64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun jsonEscape(value: String): String = value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")

    private const val USER_AGENT = "deep-student/0.11.0"
    private const val STATE_PARAM = "state"
    private const val ERROR_PARAM = "error"
    private const val CODE_PARAM = "code"
    private const val MAX_ERROR_LENGTH = 128
}

internal sealed interface CodexCallback {
    data class Code(val code: String) : CodexCallback
    data class Denied(val code: String) : CodexCallback
    data object Invalid : CodexCallback
}

internal data class CodexTokenResponse(
    val accessToken: String,
    val refreshToken: String?,
    val idToken: String?,
    val expiresInSeconds: Long?,
)

internal sealed interface CodexHttpResult {
    data class Response(val status: Int, val content: JsonValue?) : CodexHttpResult
    data class Network(val error: NetworkError) : CodexHttpResult
}

internal class CodexWireFailure(val code: CodexAuthFailureCode, val stage: CodexAuthStage, cause: Throwable? = null) :
    Exception(code.name, cause)

internal fun ProviderCancellation.check() {
    if (isCancelled()) throw CancellationException("Codex operation cancelled")
}

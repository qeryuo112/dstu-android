package com.deepstudent.services.providers.data.codex

import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.core.common.logging.NoopLogger
import com.deepstudent.platform.network.HttpLimits
import com.deepstudent.platform.network.HttpResponse
import com.deepstudent.platform.network.NetworkRequest
import com.deepstudent.platform.network.NetworkTransport
import com.deepstudent.platform.network.ResponseHead
import com.deepstudent.platform.network.SseEnd
import com.deepstudent.platform.network.SseEvent
import com.deepstudent.platform.network.SseLimits
import com.deepstudent.services.providers.api.ProviderResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.net.URI
import java.net.URLDecoder
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/** Synthetic account id shared by every fake token in these tests; never a real account. */
internal const val CODEX_ACCOUNT = "account-fixture"

/** In-memory transport for OAuth calls; queued replies override the per-endpoint defaults once. */
internal class ScriptedNetwork(private val defaults: Map<String, HttpResponse>) : NetworkTransport {
    private val replies = mutableMapOf<String, ArrayDeque<HttpResponse>>()
    val requests = CopyOnWriteArrayList<NetworkRequest>()

    fun enqueue(url: String, response: HttpResponse) {
        synchronized(replies) { replies.getOrPut(url) { ArrayDeque() }.addLast(response) }
    }

    override suspend fun execute(request: NetworkRequest, limits: HttpLimits): HttpResponse {
        requests += request
        val next = synchronized(replies) { replies[request.url]?.removeFirstOrNull() }
        return next ?: checkNotNull(defaults[URI(request.url).path]) { "unexpected Codex request ${request.url}" }
    }

    override suspend fun streamSse(
        request: NetworkRequest,
        limits: SseLimits,
        onResponse: suspend (ResponseHead) -> Unit,
        onEvent: suspend (SseEvent) -> Unit,
    ): SseEnd = error("Codex account auth never streams")

    override fun close() = Unit
}

/** Test vault seam; [save] takes buffer ownership like the app-owned encrypted vault. */
internal class MemoryVault : CodexSessionVault {
    private val lock = Mutex()
    private var stored: ByteArray? = null
    var saves = 0
        private set

    override suspend fun load(): ByteArray? = lock.withLock { stored?.copyOf() }

    override suspend fun save(serializedUtf8: ByteArray) {
        lock.withLock {
            saves += 1
            stored = serializedUtf8.copyOf()
        }
        serializedUtf8.fill(0)
    }

    override suspend fun clear() {
        lock.withLock { stored = null }
    }

    suspend fun install(session: CodexSession) {
        lock.withLock { stored = CodexSessionCodec.encode(session) }
    }
}

/** Fixed clock, endpoints and service seams; tests advance [now] to drive expiry paths. */
internal class CodexAccountHarness {
    private val base = "http://codex.test"
    var now = 1_700_000_000_000L
    val endpoints = CodexOAuthEndpoints.forTest(base)

    val tokenUrl = "$base/oauth/token"
    val deviceUserCodeUrl = "$base/device/usercode"
    val deviceTokenUrl = "$base/device/token"

    fun service(network: ScriptedNetwork, vault: MemoryVault): CodexOAuthService =
        CodexOAuthService(network, vault, NoopLogger, endpoints, { now }, SecureRandom(), HARNESS_VERSION)

    /** One reply per OAuth endpoint; [ScriptedNetwork.enqueue] overrides a default exactly once. */
    fun defaults(): Map<String, HttpResponse> = mapOf(
        URI(deviceUserCodeUrl).path to reply(
            200,
            """{"device_auth_id":"device-auth-fixture","user_code":"SYNTH-CODE","interval":1,"expires_in":60}""",
        ),
        URI(deviceTokenUrl).path to reply(400, """{"error":{"code":"authorization_pending"}}"""),
        URI(tokenUrl).path to reply(200, tokenReply(ACCESS_ROTATED, REFRESH_ROTATED)),
    )

    /** A session that is still valid but already inside the five-minute refresh window. */
    fun expiringSession(): CodexSession = CodexSession(
        accessToken = "access-expiring-fixture",
        refreshToken = "refresh-fixture",
        idToken = jwt(),
        expiresAtMillis = now + 30_000,
        account = CodexSessionAccount(CODEX_ACCOUNT),
        lastRefreshAtMillis = null,
    )

    /** The browser or device token exchange must carry the pinned grant shape and PKCE verifier. */
    fun assertExchange(network: ScriptedNetwork, redirectUri: String, code: String, verifier: String?) {
        val exchange = network.requests
            .last { it.url == tokenUrl && it.form()["grant_type"] == "authorization_code" }
            .form()
        assertEquals(code, exchange["code"])
        assertEquals(redirectUri, exchange["redirect_uri"])
        assertEquals(endpoints.clientId, exchange["client_id"])
        val presented = checkNotNull(exchange["code_verifier"])
        if (verifier != null) assertEquals(verifier, presented) else assertTrue(presented.length >= 43)
    }

    private fun jwt(): String {
        val claims = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("""{"chatgpt_account_id":"$CODEX_ACCOUNT"}""".encodeToByteArray())
        return "e30.$claims.fixture-signature"
    }

    companion object {
        const val ACCESS_ROTATED = "access-rotated-fixture"
        const val REFRESH_ROTATED = "refresh-rotated-fixture"
        const val HARNESS_VERSION = "0.11.0-test"
    }
}

internal fun reply(status: Int, body: String): HttpResponse = HttpResponse(ResponseHead(status, emptyList()), body.encodeToByteArray())

internal fun tokenReply(access: String, refresh: String?): String {
    val claims = Base64.getUrlEncoder().withoutPadding()
        .encodeToString("""{"chatgpt_account_id":"$CODEX_ACCOUNT"}""".encodeToByteArray())
    val refreshField = refresh?.let { ",\"refresh_token\":\"$it\"" }.orEmpty()
    return """{"access_token":"$access","id_token":"e30.$claims.fixture-signature","expires_in":3600$refreshField}"""
}

internal fun <T> ProviderResult<T>.succeed(): T = when (this) {
    is ProviderResult.Success -> value
    is ProviderResult.Failure -> error("expected success but failed: ${failure.code}")
    ProviderResult.Cancelled -> error("expected success but cancelled")
}

internal fun NetworkRequest.form(): Map<String, String> = checkNotNull(body).decodeToString().split('&').associate { part ->
    val (name, value) = part.split('=', limit = 2)
    URLDecoder.decode(name, Charsets.UTF_8) to URLDecoder.decode(value, Charsets.UTF_8)
}

internal fun NetworkRequest.json(): JsonValue.Object = JsonValue.parseObject(checkNotNull(body).decodeToString())

internal fun JsonValue.Object.textField(key: String): String = (this[key] as? JsonValue.Text)?.value ?: error("missing text field $key")

internal fun queryValue(uri: String, name: String): String =
    URI(uri).rawQuery.split('&').map { it.split('=', limit = 2) }.first { it[0] == name }[1]

/** Polls status until [predicate] holds; the device poll runs on the real IO dispatcher. */
internal suspend fun CodexOAuthService.awaitStatus(
    timeoutMillis: Long = 12_000,
    predicate: (CodexAuthStatus) -> Boolean,
): CodexAuthStatus {
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
    var current = status()
    while (!predicate(current)) {
        if (System.nanoTime() >= deadline) error("Codex status did not settle: $current")
        delay(20)
        current = status()
    }
    return current
}

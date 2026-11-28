package com.deepstudent.app.runtime

import com.deepstudent.core.common.logging.NoopLogger
import com.deepstudent.platform.network.HttpLimits
import com.deepstudent.platform.network.HttpResponse
import com.deepstudent.platform.network.NetworkRequest
import com.deepstudent.platform.network.NetworkTransport
import com.deepstudent.platform.network.OkHttpNetworkTransport
import com.deepstudent.platform.network.ResponseHead
import com.deepstudent.platform.network.SseEnd
import com.deepstudent.platform.network.SseEvent
import com.deepstudent.platform.network.SseLimits
import com.deepstudent.platform.security.KeyWrapper
import com.deepstudent.platform.security.SecurityFactory
import com.deepstudent.services.providers.api.ProviderCallAccess
import com.deepstudent.services.providers.api.ProviderCredentialStore
import com.deepstudent.services.providers.backend.ProviderConfigResolver
import com.deepstudent.services.providers.data.codex.CodexAuthPhase
import com.deepstudent.services.providers.data.codex.CodexBrowserLoginResult
import com.deepstudent.services.providers.data.codex.CodexOAuthService
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.URI
import java.net.URLDecoder
import java.util.Base64

/** Real encrypted vault and real HTTP share the same account service on both sides of the Responses call. */
class S305CodexAccountIntegrationTest {
    @get:Rule val directory = TemporaryFolder()
    private val case = S305ReplayFixture.case("openai_codex")

    @Test
    fun browserAccountCanCallRefreshAndReopenWithoutExposingSessionInPlaintext() = runBlocking {
        withTimeout(8_000) {
            val token = "first-fixture-lease"
            val rotated = "second-fixture-lease"
            val refreshToken = "fixture-rotation-value"
            val replies = listOf(
                oauthReply(token, refreshToken),
                ProviderReplayReply.rejection(401),
                oauthReply(rotated, null),
                s305Ack(),
            )
            usePeers(replies) { peers ->
                SecurityFactory.create(directory.root, JvmWrapping).use { ports ->
                    val vault = MainCodexSessionVault { ports.credentials }
                    AccountReplayNetwork(
                        peers,
                        listOf(TOKEN_ENDPOINT, case.endpoint, TOKEN_ENDPOINT, case.endpoint),
                    ).use { network ->
                        CodexOAuthService(network, vault).use { account ->
                            verifyBrowserAndResponses(account, network, peers, token, rotated)
                            assertFalse(account.status().toString().contains(rotated))
                        }
                    }
                    OkHttpNetworkTransport().use { network ->
                        CodexOAuthService(network, vault).use { reopened ->
                            assertEquals(CodexAuthPhase.AUTHENTICATED, reopened.status().phase)
                            assertTrue(reopened.status().hasUsableSession)
                        }
                    }
                    val record = directory.root.walkTopDown().single { it.extension == "dsc" }.readBytes().decodeToString()
                    assertFalse(record.contains(rotated) || record.contains(refreshToken))
                }
            }
        }
    }

    private suspend fun verifyBrowserAndResponses(
        account: CodexOAuthService,
        network: NetworkTransport,
        peers: List<ProviderReplayPeer>,
        first: String,
        rotated: String,
    ) {
        val attempt = account.startBrowserLogin("http://localhost:1455/auth/callback").s305Success()
        val authorization = URI(attempt.authorizationUrl)
        assertEquals("auth.openai.com", authorization.host)
        val state = authorization.rawQuery.params().getValue("state")
        account.finishBrowserLogin(attempt.attemptId, "${attempt.redirectUri}?state=not-$state&code=discard-me")
        assertEquals(CodexAuthPhase.AUTHORIZING, account.status().phase)
        val result = account.finishBrowserLogin(attempt.attemptId, "${attempt.redirectUri}?state=$state&code=fixture-code")
        assertTrue(result is CodexBrowserLoginResult.Completed)
        val resolver = ProviderConfigResolver()
        val access = ProviderCallAccess(
            ProviderCredentialStore { error("OAuth is not a BYOK route") },
            mainProviderAdapters(network, NoopLogger, allowLoopback = false, codexAuth = account),
        )
        assertEquals("ack", resolver.call(case.selection(), case.messages, case.tools, case.session, access = access).s305Success().text)
        val exchange = peers[0].captured().body.params()
        assertEquals("fixture-code", exchange["code"])
        assertEquals("authorization_code", exchange["grant_type"])
        assertTrue(checkNotNull(exchange["code_verifier"]).length >= 43)
        assertEquals("Bearer $first", peers[1].captured().headers["authorization"])
        assertEquals("refresh_token", peers[2].captured().body.params()["grant_type"])
        assertEquals("Bearer $rotated", peers[3].captured().headers["authorization"])
        assertEquals(CODEX_ACCOUNT, peers[3].captured().headers["chatgpt-account-id"])
    }

    private fun oauthReply(access: String, refresh: String?): ProviderReplayReply {
        val claims = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("""{"chatgpt_account_id":"$CODEX_ACCOUNT"}""".encodeToByteArray())
        val idToken = "e30.$claims.fixture-signature"
        val refreshField = refresh?.let { ",\"refresh_token\":\"$it\"" }.orEmpty()
        return ProviderReplayReply(
            200,
            "application/json",
            """{"access_token":"$access","expires_in":3600,"id_token":"$idToken"$refreshField}""",
        )
    }

    private suspend fun usePeers(replies: List<ProviderReplayReply>, block: suspend (List<ProviderReplayPeer>) -> Unit) {
        val peers = replies.map(::ProviderReplayPeer)
        try {
            block(peers)
        } finally {
            peers.asReversed().forEach(ProviderReplayPeer::close)
        }
    }

    private object JvmWrapping : KeyWrapper {
        override fun wrap(plaintext: ByteArray) = plaintext.copyOf()
        override fun unwrap(wrapped: ByteArray) = wrapped.copyOf()
    }

    private companion object {
        const val TOKEN_ENDPOINT = "https://auth.openai.com/oauth/token"
        const val CODEX_ACCOUNT = "fixture-end-to-end-account"
    }
}

private class AccountReplayNetwork(private val peers: List<ProviderReplayPeer>, private val expected: List<String>) : NetworkTransport {
    private val network = OkHttpNetworkTransport()
    private var used = 0

    override suspend fun execute(request: NetworkRequest, limits: HttpLimits): HttpResponse = network.execute(rewrite(request), limits)

    override suspend fun streamSse(
        request: NetworkRequest,
        limits: SseLimits,
        onResponse: suspend (ResponseHead) -> Unit,
        onEvent: suspend (SseEvent) -> Unit,
    ): SseEnd = network.streamSse(rewrite(request), limits, onResponse, onEvent)

    private fun rewrite(request: NetworkRequest): NetworkRequest {
        val step = used++
        check(request.url == expected.getOrNull(step)) { "OAuth/Responses used the wrong route or made an extra request" }
        return NetworkRequest(peers[step].origin + URI(request.url).rawPath, request.method, request.headers, request.body)
    }

    override fun close() = network.close()
}

private fun String.params(): Map<String, String> = split('&').associate { field ->
    val (key, value) = field.split('=', limit = 2)
    URLDecoder.decode(key, Charsets.UTF_8) to URLDecoder.decode(value, Charsets.UTF_8)
}

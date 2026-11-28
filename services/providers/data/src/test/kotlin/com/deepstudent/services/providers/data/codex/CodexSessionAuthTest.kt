package com.deepstudent.services.providers.data.codex

import com.deepstudent.services.providers.api.ProviderCancellation
import com.deepstudent.services.providers.api.ProviderFailureCode
import com.deepstudent.services.providers.api.ProviderResult
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Refresh and re-login behavior over in-memory transports only.
 * A rejected refresh keeps the stored session untouched and marks reauthentication;
 * only a fresh browser login restores access. Concurrent request-auth shares one
 * gated refresh, matching the frozen desktop refresh_gate semantics.
 */
class CodexSessionAuthTest {
    @Test
    fun invalidRefreshCredentialRequiresReloginThroughBrowser(): Unit = runBlocking {
        withTimeout(15_000) {
            val harness = CodexAccountHarness()
            val network = ScriptedNetwork(harness.defaults())
            val vault = MemoryVault()
            vault.install(harness.expiringSession())
            network.enqueue(harness.tokenUrl, reply(400, """{"error":{"code":"invalid_grant"}}"""))
            harness.service(network, vault).use { service ->
                val denied = service.requestAuth(ProviderCancellation { false })
                assertTrue(denied is ProviderResult.Failure)
                assertEquals(
                    ProviderFailureCode.REAUTHENTICATION_REQUIRED,
                    (denied as ProviderResult.Failure).failure.code,
                )

                val failed = service.status()
                assertEquals(CodexAuthPhase.REAUTHENTICATION_REQUIRED, failed.phase)
                assertEquals(CodexAuthFailureCode.OAUTH_REJECTED, failed.lastFailure?.code)
                assertTrue(failed.lastFailure?.reauthenticationRequired == true)
                assertEquals(1L, failed.generation)
                assertEquals(0, vault.saves)

                val start = service.startBrowserLogin("http://localhost:1455/auth/callback").succeed()
                assertEquals("S256", queryValue(start.authorizationUrl, "code_challenge_method"))
                assertTrue(
                    service.finishBrowserLogin(start.attemptId, "${start.redirectUri}?state=wrong&code=synthetic")
                        is CodexBrowserLoginResult.Failed,
                )
                assertEquals(start.attemptId, service.status().activeAttemptId)

                val state = queryValue(start.authorizationUrl, "state")
                val completed =
                    service.finishBrowserLogin(start.attemptId, "${start.redirectUri}?state=$state&code=synthetic-auth-code")
                assertTrue(completed is CodexBrowserLoginResult.Completed)
                harness.assertExchange(network, start.redirectUri, "synthetic-auth-code", verifier = null)

                val status = service.status()
                assertEquals(CodexAuthPhase.AUTHENTICATED, status.phase)
                assertEquals(2L, status.generation)
                val lease = service.requestAuth(ProviderCancellation { false }).succeed()
                assertEquals(2L, lease.generation)
                assertEquals(CODEX_ACCOUNT, lease.accountIdForRequest())
                lease.close()
            }
        }
    }

    @Test
    fun concurrentRequestAuthRefreshesOnceAndSharesTheGeneration(): Unit = runBlocking {
        withTimeout(15_000) {
            val harness = CodexAccountHarness()
            val network = ScriptedNetwork(harness.defaults())
            val vault = MemoryVault()
            vault.install(harness.expiringSession())
            harness.service(network, vault).use { service ->
                val leases = listOf(
                    async { service.requestAuth(ProviderCancellation { false }).succeed() },
                    async { service.requestAuth(ProviderCancellation { false }).succeed() },
                ).map { it.await() }
                leases.forEach { lease ->
                    assertEquals(2L, lease.generation)
                    assertEquals(CODEX_ACCOUNT, lease.accountIdForRequest())
                }
                leases.forEach { it.close() }

                val refreshes = network.requests
                    .count { it.url == harness.tokenUrl && it.form()["grant_type"] == "refresh_token" }
                assertEquals(1, refreshes)
                assertEquals(1, vault.saves)
                assertEquals(CodexAuthPhase.AUTHENTICATED, service.status().phase)
            }
        }
    }
}

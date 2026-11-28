package com.deepstudent.services.providers.data.codex

import com.deepstudent.services.providers.api.ProviderCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Device-code login over in-memory transports only; frozen desktop `manager.ts` semantics.
 * No auth server, no real account: the scripted transport plays both OAuth endpoints.
 */
class CodexDeviceLoginTest {
    @Test
    fun deviceLoginCompletesThroughPendingPollAndPersistsTheSession(): Unit = runBlocking {
        withTimeout(20_000) {
            val harness = CodexAccountHarness()
            val network = ScriptedNetwork(harness.defaults())
            val vault = MemoryVault()
            network.enqueue(harness.deviceTokenUrl, reply(400, """{"error":{"code":"authorization_pending"}}"""))
            network.enqueue(
                harness.deviceTokenUrl,
                reply(200, """{"authorization_code":"synthetic-code","code_verifier":"synthetic-verifier"}"""),
            )
            harness.service(network, vault).use { service ->
                val start = service.startDeviceLogin().succeed()
                assertEquals("SYNTH-CODE", start.userCode)
                assertEquals(harness.endpoints.deviceVerificationUrl, start.verificationUrl)
                assertEquals(1, start.pollIntervalSeconds)
                assertEquals(harness.now + 60_000, start.expiresAtMillis)
                assertEquals(CodexAuthPhase.AUTHORIZING, service.status().phase)
                assertFalse(start.toString().contains("SYNTH-CODE"))

                val status = service.awaitStatus { it.phase == CodexAuthPhase.AUTHENTICATED }
                assertEquals(2L, status.generation)
                assertEquals("***ixture", status.accountHint)
                assertTrue(status.hasUsableSession)
                assertNull(status.activeAttemptId)
                assertNull(status.lastFailure)
                assertEquals(1, vault.saves)
                assertEquals(CODEX_ACCOUNT, CodexSessionCodec.decode(checkNotNull(vault.load())).accountId)
                assertFalse(service.status().toString().contains(CodexAccountHarness.ACCESS_ROTATED))

                harness.assertExchange(network, harness.endpoints.deviceRedirectUrl, "synthetic-code", "synthetic-verifier")
                val poll = network.requests.last { it.url == harness.deviceTokenUrl }.json()
                assertEquals("device-auth-fixture", poll.textField("device_auth_id"))
                assertEquals("SYNTH-CODE", poll.textField("user_code"))
                val userCodeRequest = network.requests.first { it.url == harness.deviceUserCodeUrl }.json()
                assertEquals("test-client", userCodeRequest.textField("client_id"))

                val lease = service.requestAuth(ProviderCancellation { false }).succeed()
                assertEquals(2L, lease.generation)
                assertEquals(CODEX_ACCOUNT, lease.accountIdForRequest())
                lease.close()
            }
        }
    }

    @Test
    fun pendingDeviceLoginCancelsWithoutResurrectingTheAttempt(): Unit = runBlocking {
        withTimeout(10_000) {
            val harness = CodexAccountHarness()
            val network = ScriptedNetwork(harness.defaults())
            val vault = MemoryVault()
            harness.service(network, vault).use { service ->
                val start = service.startDeviceLogin().succeed()
                assertTrue(service.cancelLogin(start.attemptId) is CodexBrowserLoginResult.Cancelled)
                assertTrue(service.cancelLogin(start.attemptId) is CodexBrowserLoginResult.Failed)
                delay(150)

                val status = service.status()
                assertEquals(CodexAuthPhase.SIGNED_OUT, status.phase)
                assertNull(status.activeAttemptId)
                assertNull(status.lastFailure)
                assertEquals(1L, status.generation)
                assertEquals(0, vault.saves)
                assertTrue(network.requests.none { it.url == harness.deviceTokenUrl })
            }
        }
    }

    @Test
    fun expiredDeviceAttemptTimesOutWithoutRequiringRelogin(): Unit = runBlocking {
        withTimeout(15_000) {
            val harness = CodexAccountHarness()
            val network = ScriptedNetwork(harness.defaults())
            val vault = MemoryVault()
            harness.service(network, vault).use { service ->
                service.startDeviceLogin().succeed()
                harness.now += 120_000

                val status = service.awaitStatus { it.lastFailure?.code == CodexAuthFailureCode.TIMEOUT }
                assertEquals(CodexAuthPhase.SIGNED_OUT, status.phase)
                assertNull(status.activeAttemptId)
                assertFalse(status.hasUsableSession)
                assertEquals(1L, status.generation)
                assertEquals(0, vault.saves)
            }
        }
    }
}

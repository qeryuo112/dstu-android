package com.deepstudent.app.runtime

import com.deepstudent.services.providers.api.ProviderFailureCode
import com.deepstudent.services.providers.api.ProviderResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class S305CodexTransportTest {
    private val case = S305ReplayFixture.case("openai_codex")

    @Test
    fun refreshesAtMostOnceWithUpdatedAccountHeadersAndSameRequestBody() = runBlocking {
        ProviderReplayPeer(ProviderReplayReply.rejection(401)).use { initial ->
            ProviderReplayPeer(s305Ack()).use { after ->
                S305ReplaySession(case, listOf(initial, after)).use { session ->
                    assertEquals("ack", session.call().s305Success().text)
                    val before = initial.captured()
                    val retried = after.captured()
                    session.assertWireRequest(before)
                    assertEquals(before.body, retried.body)
                    assertEquals(initial.origin.substringAfter("://"), before.headers["host"])
                    assertEquals(after.origin.substringAfter("://"), retried.headers["host"])
                    val changedHeaders = listOf("authorization", "host")
                    assertEquals(before.headers - changedHeaders, retried.headers - changedHeaders)
                    assertEquals("Bearer ${session.auth.rotateTo}", retried.headers["authorization"])
                    assertEquals(listOf(42L), session.auth.refreshed)
                    assertTrue(session.auth.marked.isEmpty())
                    session.assertRedacted()
                }
            }
        }
    }

    @Test
    fun secondUnauthorizedMarksJustTheRefreshedGenerationAndDoesNotRetryAgain() = runBlocking {
        ProviderReplayPeer(ProviderReplayReply.rejection(401)).use { initial ->
            ProviderReplayPeer(ProviderReplayReply.rejection(401)).use { after ->
                S305ReplaySession(case, listOf(initial, after)).use { session ->
                    assertS305Failure(ProviderFailureCode.REAUTHENTICATION_REQUIRED, session.call { error("401 cannot emit events") })
                    assertEquals(2, session.requests)
                    assertEquals(listOf(42L), session.auth.refreshed)
                    assertEquals(listOf(43L), session.auth.marked)
                    session.assertRedacted()
                }
            }
        }
    }

    @Test
    fun cancellingRefreshDoesNotResendOrInvalidateTheAccount() = runBlocking {
        ProviderReplayPeer(ProviderReplayReply.rejection(401)).use { peer ->
            val auth = S305ReplayAuth().apply { refreshResult = ProviderResult.Cancelled }
            S305ReplaySession(case, listOf(peer), auth).use { session ->
                assertEquals(ProviderResult.Cancelled, session.call { error("cancel cannot emit events") })
                assertEquals(1, session.requests)
                assertTrue(auth.marked.isEmpty())
                session.assertRedacted()
            }
        }
    }

    @Test
    fun oversizedCodexRejectionIsNotClassifiedFromAnUntrustedPrefix() = runBlocking {
        val body = "usage_limit_reached" + REPLAY_PRIVATE_DETAIL.repeat(10_000)
        val reply = ProviderReplayReply(404, "application/json", body)
        ProviderReplayPeer(reply).use { peer ->
            S305ReplaySession(case, listOf(peer)).use { session ->
                assertS305Failure(ProviderFailureCode.PROVIDER_ERROR, session.call { error("error cannot emit events") })
                assertEquals(1, session.requests)
                assertTrue(session.auth.refreshed.isEmpty())
                session.assertRedacted()
            }
        }
    }

    @Test
    fun distinguishesCodexQuota404FromOther404WithoutLeakingTheBody() = runBlocking {
        val bodies = mapOf(
            "usage_limit_reached" to ProviderFailureCode.RATE_LIMITED,
            "fixture_other_404" to ProviderFailureCode.PROVIDER_ERROR,
        )
        for ((code, mapped) in bodies) {
            val reply = ProviderReplayReply(404, "application/json", """{"error":{"code":"$code","message":"$REPLAY_PRIVATE_DETAIL"}}""")
            ProviderReplayPeer(reply).use { peer ->
                S305ReplaySession(case, listOf(peer)).use { session ->
                    assertS305Failure(mapped, session.call { error("error cannot emit events") })
                    assertEquals(1, session.requests)
                    assertTrue(session.auth.refreshed.isEmpty())
                    session.assertRedacted()
                }
            }
        }
    }
}

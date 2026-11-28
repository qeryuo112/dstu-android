package com.deepstudent.app.runtime

import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.services.providers.api.ProviderCancellation
import com.deepstudent.services.providers.api.ProviderResult
import com.deepstudent.services.providers.api.ProviderStopReason
import com.deepstudent.services.providers.api.ProviderStreamEvent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The explicit-Responses candidate reuses the S3.05-verified internal wire components. */
class S308FrozenResponsesTest {
    @Test
    fun responses() = runBlocking {
        val case = S308EndpointFixture.responsesCases.single()
        val frames = listOf(
            """{"type":"response.output_text.delta","delta":"哈"}""",
            """{"type":"response.completed","response":{"output_text":"哈"}}""",
        )
        ProviderReplayPeer(ProviderReplayReply.sse(frames)).use { peer ->
            S308EndpointSession(case, peer).use { session ->
                val received = mutableListOf<ProviderStreamEvent>()
                val result = session.call { received += it }.s308Success()
                assertEquals("哈", result.text)
                assertEquals(ProviderStopReason.STOP, result.stopReason)
                assertTrue(received.any { it is ProviderStreamEvent.TextDelta })
                assertEquals(1, session.requests)
                val captured = session.assertRecordedRequest()
                assertTrue("responses endpoint must stay on /responses", captured.path.endsWith("/responses"))
                session.assertNoLeakedSecrets()
                writeS308Receipt(
                    case,
                    "responses",
                    JsonValue.Object.Builder()
                        .put("path", captured.path).put("explicitProtocol", "openai_responses")
                        .put("noChatFallback", true).build(),
                )
            }
        }
    }
}

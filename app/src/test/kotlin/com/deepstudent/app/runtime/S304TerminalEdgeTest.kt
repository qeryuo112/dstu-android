package com.deepstudent.app.runtime

import com.deepstudent.services.providers.api.ProviderFailureCode
import com.deepstudent.services.providers.api.ProviderResult
import com.deepstudent.services.providers.api.ProviderStreamEvent
import com.deepstudent.services.providers.api.ProviderStreamResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class S304TerminalEdgeTest {
    @Test
    fun finishedChoicesWithCleanHttpEofCompleteAfterUsage() = runBlocking {
        val usage = """{"choices":[],"usage":{"prompt_tokens":3,"completion_tokens":1}}"""
        replay(ProviderReplayReply.sse(listOf(FINISHED, usage))) { result, events ->
            assertTrue(result is ProviderResult.Success)
            assertEquals("part", (result as ProviderResult.Success).value.text)
            assertEquals(1L, result.value.usage?.outputTokens)
            assertTrue(events.last() is ProviderStreamEvent.Completed)
        }
    }

    @Test
    fun unfinishedChoiceDoesNotCompleteAtCleanEof() = runBlocking {
        val partialChoice = """{"choices":[{"index":0,"delta":{"content":"part"},"finish_reason":"stop"},""" +
            """{"index":1,"delta":{"content":"more"},"finish_reason":null}]}"""
        replay(ProviderReplayReply.sse(listOf(partialChoice))) { result, events ->
            assertTruncated(result, events)
        }
    }

    @Test
    fun truncatedFrameAfterFinishIsNotCleanEof() = runBlocking {
        val truncated = ProviderReplayReply(200, "text/event-stream", "data: $FINISHED\n\ndata: {\"choices\"\n")
        replay(truncated) { result, events ->
            assertTruncated(result, events)
        }
    }

    @Test
    fun emptyUpstreamPayloadNeverCompletesEvenWithDone() = runBlocking {
        val empty = """{"choices":[{"delta":{},"finish_reason":"stop"}]}"""
        replay(ProviderReplayReply.sse(listOf(empty, "[DONE]"))) { result, events ->
            assertTrue(result is ProviderResult.Failure)
            assertFalse(events.any { it is ProviderStreamEvent.Completed })
        }
    }

    private fun assertTruncated(result: ProviderResult<*>, events: List<ProviderStreamEvent>) {
        assertTrue(result is ProviderResult.Failure)
        assertEquals(ProviderFailureCode.STREAM_TRUNCATED, (result as ProviderResult.Failure).failure.code)
        assertTrue(events.any { it is ProviderStreamEvent.TextDelta })
        assertFalse(events.any { it is ProviderStreamEvent.Completed })
    }

    private suspend fun replay(
        reply: ProviderReplayReply,
        verify: (ProviderResult<ProviderStreamResult>, List<ProviderStreamEvent>) -> Unit,
    ) {
        val case = S304ProviderFixture.case("qwen")
        ProviderReplayPeer(reply).use { peer ->
            ProviderReplaySession(case, peer).use { session ->
                val events = mutableListOf<ProviderStreamEvent>()
                verify(session.call { events += it }, events)
                session.assertNoLeakedSecrets()
            }
        }
    }

    private companion object {
        const val FINISHED = """{"choices":[{"index":0,"delta":{"content":"part"},"finish_reason":"stop"}]}"""
    }
}

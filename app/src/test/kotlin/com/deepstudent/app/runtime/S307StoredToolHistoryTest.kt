package com.deepstudent.app.runtime

import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.features.chat.api.BlockDetails
import com.deepstudent.features.chat.api.BlockStorage
import com.deepstudent.features.chat.api.ChatBlock
import com.deepstudent.features.chat.api.ChatMessage
import com.deepstudent.features.chat.api.MessagePayload
import com.deepstudent.services.providers.api.ProviderMessage
import com.deepstudent.services.providers.api.ProviderRole
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class S307StoredToolHistoryTest {
    @Test
    fun storedToolResultReplaysWithOneSignatureOnlyOnItsOriginalTarget() = runBlocking {
        val case = S307ReplayFixture.case("google-signature-history")
        val history = storedHistory(case.historyScope)
        assertEquals(listOf(ProviderRole.ASSISTANT, ProviderRole.TOOL), history.map { it.role })
        assertEquals("call_fixture_1", history[1].toolCallId)
        assertEquals(null, history[1].nativeContent)
        assertWireSignature(case, history, crossTarget = false)
        assertWireSignature(case, storedHistory("other-target"), crossTarget = true)
    }

    private suspend fun assertWireSignature(case: S307Case, history: List<ProviderMessage>, crossTarget: Boolean) {
        val frame = """{"candidates":[{"content":{"parts":[{"text":"ack"}]},"finishReason":"STOP"}]}"""
        ProviderReplayPeer(ProviderReplayReply.sse(listOf(frame))).use { peer ->
            S307ReplaySession(case, listOf(peer), crossTarget).use { session ->
                val user = case.providerMessages().last { it.role == ProviderRole.USER }
                assertEquals("ack", session.call(messages = listOf(user) + history + user).s307Success().text)
                val wire = peer.captured().body
                if (crossTarget) {
                    assertFalse(wire.contains("fixture-private-tool-state"))
                    assertFalse(wire.contains("\"thoughtSignature\""))
                } else {
                    assertEquals(1, Regex("\"thoughtSignature\"").findAll(wire).count())
                    assertTrue(wire.contains("fixture-private-tool-state"))
                    assertTrue(wire.contains("\"functionCall\""))
                    assertTrue(wire.contains("\"functionResponse\""))
                }
            }
        }
    }

    private fun storedHistory(scope: String): List<ProviderMessage> {
        val metadata = JsonValue.parseObject(
            """{"stateScope":"placeholder","toolResults":[{"blockId":"blk_tool","thoughtSignature":"fixture-private-tool-state"}]}""",
        )
        val meta = JsonValue.Object.Builder().putAll(metadata).put("stateScope", scope).build()
        val message = ChatMessage("msg_tool", "sess_tool", "assistant", listOf("blk_tool"), 1, payload = MessagePayload(metadata = meta))
        val finished = ChatBlock(
            "blk_tool",
            message.id,
            "mcp_tool",
            "success",
            0,
            BlockDetails(toolName = "find", toolInput = JsonValue.Object.EMPTY, toolOutput = JsonValue.obj("ok" to JsonValue.bool(true))),
            storage = BlockStorage(toolCallId = "call_fixture_1"),
        )
        val incomplete = finished.copy(id = "blk_incomplete", status = "running", storage = BlockStorage(toolCallId = "unexecuted"))
        return storedToolHistory(message, listOf(finished, incomplete))
    }
}

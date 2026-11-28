package com.deepstudent.features.chat.backend

import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.features.chat.api.BlockDetails
import com.deepstudent.features.chat.api.BlockField
import com.deepstudent.features.chat.api.ChatBlock
import com.deepstudent.features.chat.api.ChatConversation
import com.deepstudent.features.chat.api.ChatMessage
import com.deepstudent.features.chat.api.ChatRepository
import com.deepstudent.features.chat.api.ChatSession
import com.deepstudent.features.chat.api.MessagePayload
import com.deepstudent.features.chat.api.TurnSnapshot
import com.deepstudent.features.chat.backend.application.LoadTurnSnapshot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnSnapshotTest {
    @Test fun freezeOwnsEveryMutableContainerAndIsDeterministic() {
        val nested = mutableListOf<JsonValue>(JsonValue.Number.integer(Long.MAX_VALUE))
        val metadata = JsonValue.Object.from(mapOf("nested" to JsonValue.Array(nested)))
        val ids = mutableListOf("b")
        val clears = mutableSetOf(BlockField.ERROR)
        val messages = mutableListOf(ChatMessage("m", "s", "assistant", ids, 1, payload = MessagePayload(metadata = metadata)))
        val blocks = mutableListOf(ChatBlock("b", "m", "content", "success", 0, details = BlockDetails(clear = clears)))
        val input = ChatConversation(ChatSession("s", "chat", "a", "b"), messages, blocks)
        val frozen = TurnSnapshot.freeze(input)
        assertEquals(frozen, TurnSnapshot.freeze(input))
        ids += "changed"
        nested.clear()
        clears.clear()
        messages.clear()
        blocks.clear()
        val value = frozen.conversation
        assertEquals(listOf("b"), value.messages.single().blockIds)
        assertEquals(
            Long.MAX_VALUE,
            (
                ((value.messages.single().payload.metadata as JsonValue.Object)["nested"] as JsonValue.Array)
                    .values.single() as JsonValue.Number
                ).asLongOrNull(),
        )
        assertEquals(setOf(BlockField.ERROR), value.blocks.single().details.clear)
        assertThrows(UnsupportedOperationException::class.java) { (value.messages as MutableList).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (value.messages.single().blockIds as MutableList).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (value.blocks.single().details.clear as MutableSet).clear() }
    }

    @Test fun backendReadsTheSuppliedRepositoryAndFreezesItsResult() = runTest {
        val messages = mutableListOf(ChatMessage("m", "s", "user", emptyList(), 10))
        val chat = ChatConversation(ChatSession("s", "chat", "a", "b"), messages, emptyList())
        val repo = object : ChatRepository {
            override suspend fun load(sessionId: String): ChatConversation? = if (sessionId == "s") chat else null
            override suspend fun save(conversation: ChatConversation) = error("not called")
        }
        val consumer = LoadTurnSnapshot(repo)
        val snapshot = consumer("s")!!
        messages.clear()
        assertEquals("m", snapshot.conversation.messages.single().id)
        assertTrue(consumer("missing") == null)
    }
}

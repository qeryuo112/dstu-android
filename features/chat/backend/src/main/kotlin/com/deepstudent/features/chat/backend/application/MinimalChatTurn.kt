package com.deepstudent.features.chat.backend.application

import com.deepstudent.core.common.id.TaskId
import com.deepstudent.features.chat.api.BlockDetails
import com.deepstudent.features.chat.api.ChatBlock
import com.deepstudent.features.chat.api.ChatConversation
import com.deepstudent.features.chat.api.ChatMessage
import com.deepstudent.features.chat.api.ChatSession
import java.time.Instant
import java.util.UUID

/** S1 sends independent requests, so each successful task owns one session and two messages. */
internal fun completedMinimalChat(taskId: TaskId, prompt: String, text: String, startedAt: Instant, endedAt: Instant): ChatConversation {
    val sessionId = "sess_${taskId.value.removePrefix("task_")}"
    val session = ChatSession(
        id = sessionId,
        mode = "general_chat",
        createdAt = databaseTimestamp(startedAt),
        updatedAt = databaseTimestamp(endedAt),
    )
    val user = message(taskId, sessionId, "user", prompt, startedAt)
    val assistant = message(taskId, sessionId, "assistant", text, endedAt)
    return ChatConversation(session, listOf(user.first, assistant.first), listOf(user.second, assistant.second))
}

private fun message(taskId: TaskId, sessionId: String, role: String, content: String, time: Instant): Pair<ChatMessage, ChatBlock> {
    val id = "msg_${taskRowId(taskId, role, "message")}"
    val blockId = "blk_${taskRowId(taskId, role, "block")}"
    val message = ChatMessage(id, sessionId, role, listOf(blockId), time.toEpochMilli())
    val block = ChatBlock(blockId, id, "content", "success", 0, BlockDetails(content = content))
    return message to block
}

private fun taskRowId(taskId: TaskId, role: String, row: String): UUID =
    UUID.nameUUIDFromBytes("${taskId.value}:$role:$row".encodeToByteArray())

private fun databaseTimestamp(time: Instant): String = time.toString().removeSuffix("Z") + "+00:00"

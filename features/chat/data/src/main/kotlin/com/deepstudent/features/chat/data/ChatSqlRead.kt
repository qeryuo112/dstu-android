package com.deepstudent.features.chat.data

import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.features.chat.api.BlockDetails
import com.deepstudent.features.chat.api.BlockStorage
import com.deepstudent.features.chat.api.BlockTiming
import com.deepstudent.features.chat.api.ChatBlock
import com.deepstudent.features.chat.api.ChatConversation
import com.deepstudent.features.chat.api.ChatMessage
import com.deepstudent.features.chat.api.ChatSession
import com.deepstudent.features.chat.api.MessageLinks
import com.deepstudent.features.chat.api.MessagePayload
import com.deepstudent.features.chat.api.MessageStorage
import com.deepstudent.features.chat.api.SessionDetails
import com.deepstudent.features.chat.api.SessionStorage
import com.deepstudent.platform.database.SqlReadOperations
import com.deepstudent.platform.database.SqlRow

internal fun SqlReadOperations.readChat(sessionId: String): ChatConversation? {
    val argument = listOf(text(sessionId))
    val session = query(SESSION_SQL, argument, ::sessionRow).singleOrNull() ?: return null
    val messages = query(MESSAGE_SQL, argument, ::messageRow)
    val blocks = query(BLOCK_SQL, argument, ::blockRow)
    return ChatConversation(session, messages, blocks)
}

private val SESSION_SQL = """SELECT id,mode,created_at,updated_at,title,persist_status,metadata_json,
    description,summary_hash,group_id,title_locked,workspace_id,device_id,local_version,deleted_at,
    tags_hash,last_compaction_id FROM chat_v2_sessions WHERE id=?
""".trimIndent()
private val MESSAGE_SQL = """SELECT id,session_id,role,block_ids_json,timestamp,persistent_stable_id,
    parent_id,supersedes,meta_json,attachments_json,active_variant_id,variants_json,shared_context_json,
    device_id,local_version,updated_at,deleted_at FROM chat_v2_messages
    WHERE session_id=? ORDER BY timestamp ASC,rowid ASC
""".trimIndent()

// This is the frozen desktop full-session query, including SQLite's NULL-first and tie behavior.
private val BLOCK_SQL = """SELECT b.id,b.message_id,b.block_type,b.status,b.block_index,b.content,
    b.tool_name,b.tool_input_json,b.tool_output_json,b.citations_json,b.error,b.started_at,b.ended_at,
    b.variant_id,b.first_chunk_at,b.device_id,b.local_version,b.updated_at,b.deleted_at,b.compacted_at,
    b.llm_content,b.tool_call_id,b.round_text FROM chat_v2_blocks b
    INNER JOIN chat_v2_messages m ON b.message_id=m.id WHERE m.session_id=?
    ORDER BY m.timestamp ASC,COALESCE(b.first_chunk_at,b.started_at) ASC,b.block_index ASC
""".trimIndent()

private fun sessionRow(row: SqlRow): ChatSession = ChatSession(
    id = row.text(0),
    mode = row.text(1),
    createdAt = row.text(2),
    updatedAt = row.text(3),
    persistStatus = row.text(5),
    details = SessionDetails(
        title = row.optionalText(4),
        metadata = row.optionalJson(6),
        description = row.optionalText(7),
        summaryHash = row.optionalText(8),
        groupId = row.optionalText(9),
        titleLocked = row.long(10) != 0L,
    ),
    storage = SessionStorage(
        workspaceId = row.optionalText(11),
        deviceId = row.optionalText(12),
        localVersion = row.optionalLong(13),
        deletedAt = row.optionalText(14),
        tagsHash = row.optionalText(15),
        lastCompactionId = row.optionalText(16),
    ),
)

private fun messageRow(row: SqlRow): ChatMessage = ChatMessage(
    id = row.text(0),
    sessionId = row.text(1),
    role = row.text(2),
    blockIds = parseIds(row.text(3)),
    timestamp = row.long(4),
    links = MessageLinks(
        persistentStableId = row.optionalText(5),
        parentId = row.optionalText(6),
        supersedes = row.optionalText(7),
        activeVariantId = row.optionalText(10),
    ),
    payload = MessagePayload(
        metadata = row.optionalJson(8),
        attachments = row.optionalJson(9),
        variants = row.optionalJson(11),
        sharedContext = row.optionalJson(12),
    ),
    storage = MessageStorage(
        deviceId = row.optionalText(13),
        localVersion = row.optionalLong(14),
        updatedAt = row.optionalText(15),
        deletedAt = row.optionalText(16),
    ),
)

private fun blockRow(row: SqlRow): ChatBlock = ChatBlock(
    id = row.text(0),
    messageId = row.text(1),
    type = row.text(2),
    status = row.text(3),
    blockIndex = row.long(4),
    details = BlockDetails(
        content = row.optionalText(5),
        toolName = row.optionalText(6),
        toolInput = row.optionalJson(7),
        toolOutput = row.optionalJson(8),
        citations = row.optionalJson(9),
        error = row.optionalText(10),
        variantId = row.optionalText(13),
    ),
    timing = BlockTiming(
        startedAt = row.optionalLong(11),
        endedAt = row.optionalLong(12),
        firstChunkAt = row.optionalLong(14),
    ),
    storage = BlockStorage(
        deviceId = row.optionalText(15),
        localVersion = row.optionalLong(16),
        updatedAt = row.optionalText(17),
        deletedAt = row.optionalText(18),
        compactedAt = row.optionalLong(19),
        llmContent = row.optionalText(20),
        toolCallId = row.optionalText(21),
        roundText = row.optionalText(22),
    ),
)

private fun parseIds(raw: String): List<String> = (
    JsonValue.parse(raw) as? JsonValue.Array
        ?: throw IllegalArgumentException("block_ids_json must be an array")
    ).values.map {
    (it as? JsonValue.Text)?.value ?: throw IllegalArgumentException("block ID must be text")
}
private fun SqlRow.optionalText(index: Int): String? = if (isNull(index)) null else text(index)
private fun SqlRow.optionalLong(index: Int): Long? = if (isNull(index)) null else long(index)
private fun SqlRow.optionalJson(index: Int): JsonValue? = optionalText(index)?.let(JsonValue::parse)

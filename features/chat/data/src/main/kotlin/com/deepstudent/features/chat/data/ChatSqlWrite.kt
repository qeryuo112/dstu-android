package com.deepstudent.features.chat.data

import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.features.chat.api.BlockField
import com.deepstudent.features.chat.api.ChatBlock
import com.deepstudent.features.chat.api.ChatConversation
import com.deepstudent.features.chat.api.ChatMessage
import com.deepstudent.features.chat.api.ChatSession
import com.deepstudent.features.chat.api.MessageField
import com.deepstudent.features.chat.api.SessionField
import com.deepstudent.platform.database.SqlOperations
import com.deepstudent.platform.database.SqlValue

internal fun validateChat(chat: ChatConversation) {
    require(chat.session.id.isNotBlank())
    require(chat.messages.all { it.sessionId == chat.session.id }) { "message belongs to another session" }
    require(chat.messages.map { it.id }.distinct().size == chat.messages.size) { "duplicate message ID" }
    require(chat.blocks.map { it.id }.distinct().size == chat.blocks.size) { "duplicate block ID" }
    require(chat.messages.all { it.id.isNotBlank() } && chat.blocks.all { it.id.isNotBlank() })
}

internal fun SqlOperations.putSession(s: ChatSession) {
    val row = Upsert("chat_v2_sessions")
    val details = s.details
    row.required("id", text(s.id)).required("mode", text(s.mode))
        .required("created_at", text(s.createdAt), update = false).required("updated_at", text(s.updatedAt))
        .required("persist_status", text(s.persistStatus))
    row.optional("title", details.title, SessionField.TITLE in details.clear, ::text)
    row.optional("metadata_json", details.metadata, SessionField.METADATA in details.clear, ::json)
    row.optional("description", details.description, SessionField.DESCRIPTION in details.clear, ::text)
    row.optional("summary_hash", details.summaryHash, SessionField.SUMMARY_HASH in details.clear, ::text)
    row.optional("group_id", details.groupId, SessionField.GROUP_ID in details.clear, ::text)
    details.titleLocked?.let { row.required("title_locked", SqlValue.Integer(if (it) 1 else 0)) }
    row.execute(this)
}

internal fun SqlOperations.putMessage(m: ChatMessage) {
    checkOwner("chat_v2_messages", "session_id", m.id, m.sessionId)
    val row = Upsert("chat_v2_messages")
    val links = m.links
    val payload = m.payload
    row.required("id", text(m.id)).required("session_id", text(m.sessionId), update = false)
        .required("role", text(m.role)).required("block_ids_json", json(JsonValue.Array(m.blockIds.map(JsonValue::Text))))
        .required("timestamp", integer(m.timestamp))
    row.optional("persistent_stable_id", links.persistentStableId, MessageField.PERSISTENT_STABLE_ID in payload.clear, ::text)
    row.optional("parent_id", links.parentId, MessageField.PARENT_ID in payload.clear, ::text)
    row.optional("supersedes", links.supersedes, MessageField.SUPERSEDES in payload.clear, ::text)
    row.optional("active_variant_id", links.activeVariantId, MessageField.ACTIVE_VARIANT_ID in payload.clear, ::text)
    row.optional("meta_json", payload.metadata, MessageField.METADATA in payload.clear, ::json)
    row.optional("attachments_json", payload.attachments, MessageField.ATTACHMENTS in payload.clear, ::json)
    row.optional("variants_json", payload.variants, MessageField.VARIANTS in payload.clear, ::json)
    row.optional("shared_context_json", payload.sharedContext, MessageField.SHARED_CONTEXT in payload.clear, ::json)
    row.execute(this)
}

internal fun SqlOperations.putBlock(b: ChatBlock, sessionId: String) {
    val owner = query("SELECT session_id FROM chat_v2_messages WHERE id=?", listOf(text(b.messageId))) { it.text(0) }
    require(owner.singleOrNull() == sessionId) { "block message is missing or belongs to another session" }
    checkOwner("chat_v2_blocks", "message_id", b.id, b.messageId)
    val row = Upsert("chat_v2_blocks")
    val details = b.details
    val timing = b.timing
    row.required("id", text(b.id)).required("message_id", text(b.messageId), update = false)
        .required("block_type", text(b.type)).required("status", text(b.status))
        .required("block_index", integer(b.blockIndex))
    row.optional("content", details.content, BlockField.CONTENT in details.clear, ::text)
    if (details.content != null || BlockField.CONTENT in details.clear) row.invalidateReplayOnContentChange()
    row.optional("tool_name", details.toolName, BlockField.TOOL_NAME in details.clear, ::text)
    row.optional("tool_input_json", details.toolInput, BlockField.TOOL_INPUT in details.clear, ::json)
    row.optional("tool_output_json", details.toolOutput, BlockField.TOOL_OUTPUT in details.clear, ::json)
    row.optional("citations_json", details.citations, BlockField.CITATIONS in details.clear, ::json)
    row.optional("error", details.error, BlockField.ERROR in details.clear, ::text)
    row.optional("variant_id", details.variantId, BlockField.VARIANT_ID in details.clear, ::text)
    row.optional("started_at", timing.startedAt, BlockField.STARTED_AT in details.clear, ::integer)
    row.optional("ended_at", timing.endedAt, BlockField.ENDED_AT in details.clear, ::integer)
    row.optional("first_chunk_at", timing.firstChunkAt, BlockField.FIRST_CHUNK_AT in details.clear, ::integer)
    row.execute(this)
}

private fun SqlOperations.checkOwner(table: String, column: String, id: String, owner: String) {
    val existing = query("SELECT $column FROM $table WHERE id=?", listOf(text(id))) { it.text(0) }
    require(existing.isEmpty() || existing.single() == owner) { "existing row belongs to another parent" }
}

/** SQL identifiers are fixed internally, never sourced from the caller. */
private class Upsert(private val table: String) {
    private val columns = mutableListOf<String>()
    private val values = mutableListOf<SqlValue>()
    private val updates = mutableListOf<String>()
    private val comparisons = mutableListOf<String>()

    fun required(column: String, value: SqlValue, update: Boolean = column != "id"): Upsert = apply {
        columns += column
        values += value
        if (update) {
            updates += "$column=excluded.$column"
            comparisons += "$table.$column IS NOT excluded.$column"
        }
    }

    fun <T : Any> optional(column: String, value: T?, clear: Boolean, convert: (T?) -> SqlValue) {
        require(value == null || !clear) { "field cannot be supplied and cleared" }
        if (value != null || clear) required(column, convert(value))
    }

    fun invalidateReplayOnContentChange() {
        updates += "llm_content=CASE WHEN chat_v2_blocks.content IS excluded.content " +
            "THEN chat_v2_blocks.llm_content ELSE NULL END"
    }

    fun execute(db: SqlOperations) {
        val changed = comparisons.joinToString(" OR ")
        val sql = "INSERT INTO $table (${columns.joinToString()}) VALUES (${columns.joinToString { "?" }}) " +
            "ON CONFLICT(id) DO UPDATE SET ${updates.joinToString()} WHERE $changed"
        db.execute(sql, values)
    }
}

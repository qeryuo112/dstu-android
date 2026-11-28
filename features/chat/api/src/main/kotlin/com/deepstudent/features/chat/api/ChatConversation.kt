package com.deepstudent.features.chat.api

import com.deepstudent.core.common.json.JsonValue

/** Selects the session, messages and blocks participating in a load or upsert. */
data class ChatConversation(val session: ChatSession, val messages: List<ChatMessage>, val blocks: List<ChatBlock>)

data class ChatSession(
    val id: String,
    val mode: String,
    val createdAt: String,
    val updatedAt: String,
    val persistStatus: String = "active",
    val details: SessionDetails = SessionDetails(),
    val storage: SessionStorage = SessionStorage(),
)

/** [clear] lists fields to set to SQL NULL. A null value outside that set leaves the field intact. */
data class SessionDetails(
    val title: String? = null,
    val metadata: JsonValue? = null,
    val description: String? = null,
    val summaryHash: String? = null,
    val groupId: String? = null,
    val titleLocked: Boolean? = null,
    val clear: Set<SessionField> = emptySet(),
)

enum class SessionField { TITLE, METADATA, DESCRIPTION, SUMMARY_HASH, GROUP_ID }

/** Read-only columns owned by sync, workspace, tags, and compaction. */
data class SessionStorage(
    val workspaceId: String? = null,
    val deviceId: String? = null,
    val localVersion: Long? = null,
    val deletedAt: String? = null,
    val tagsHash: String? = null,
    val lastCompactionId: String? = null,
)

data class ChatMessage(
    val id: String,
    val sessionId: String,
    val role: String,
    val blockIds: List<String>,
    val timestamp: Long,
    val links: MessageLinks = MessageLinks(),
    val payload: MessagePayload = MessagePayload(),
    val storage: MessageStorage = MessageStorage(),
) {
    /** Typed projection of the stored desktop variant array. */
    fun variantItems(): List<ChatVariant>? = (payload.variants as? JsonValue.Array)?.values?.map {
        ChatVariant(it as? JsonValue.Object ?: throw IllegalArgumentException("variant must be an object"))
    }
}

data class MessageLinks(
    val persistentStableId: String? = null,
    val parentId: String? = null,
    val supersedes: String? = null,
    val activeVariantId: String? = null,
)

data class MessagePayload(
    val metadata: JsonValue? = null,
    val attachments: JsonValue? = null,
    /** The entire stored JSON value, including unrecognized fields and explicit JSON null. */
    val variants: JsonValue? = null,
    val sharedContext: JsonValue? = null,
    val clear: Set<MessageField> = emptySet(),
)

enum class MessageField {
    PERSISTENT_STABLE_ID,
    PARENT_ID,
    SUPERSEDES,
    METADATA,
    ATTACHMENTS,
    ACTIVE_VARIANT_ID,
    VARIANTS,
    SHARED_CONTEXT,
}

data class MessageStorage(
    val deviceId: String? = null,
    val localVersion: Long? = null,
    val updatedAt: String? = null,
    val deletedAt: String? = null,
)

data class ChatBlock(
    val id: String,
    val messageId: String,
    val type: String,
    val status: String,
    val blockIndex: Long,
    val details: BlockDetails = BlockDetails(),
    val timing: BlockTiming = BlockTiming(),
    val storage: BlockStorage = BlockStorage(),
)

data class BlockDetails(
    val content: String? = null,
    val toolName: String? = null,
    val toolInput: JsonValue? = null,
    val toolOutput: JsonValue? = null,
    val citations: JsonValue? = null,
    val error: String? = null,
    val variantId: String? = null,
    val clear: Set<BlockField> = emptySet(),
)

data class BlockTiming(val startedAt: Long? = null, val endedAt: Long? = null, val firstChunkAt: Long? = null)

enum class BlockField {
    CONTENT,
    TOOL_NAME,
    TOOL_INPUT,
    TOOL_OUTPUT,
    CITATIONS,
    ERROR,
    STARTED_AT,
    ENDED_AT,
    VARIANT_ID,
    FIRST_CHUNK_AT,
}

/** Read projection for sync, replay and compaction columns. */
data class BlockStorage(
    val deviceId: String? = null,
    val localVersion: Long? = null,
    val updatedAt: String? = null,
    val deletedAt: String? = null,
    val compactedAt: Long? = null,
    val llmContent: String? = null,
    val toolCallId: String? = null,
    val roundText: String? = null,
)

/** A lossless typed view over one desktop variant; [value] retains unknown and null members. */
data class ChatVariant(val value: JsonValue.Object) {
    val id: String? get() = (value["id"] as? JsonValue.Text)?.value
    val modelId: String? get() = (value["modelId"] as? JsonValue.Text)?.value
    val configId: String? get() = (value["configId"] as? JsonValue.Text)?.value
    val blockIds: List<String>? get() = (value["blockIds"] as? JsonValue.Array)?.values?.map {
        (it as? JsonValue.Text)?.value ?: throw IllegalArgumentException("variant block ID must be text")
    }
    val status: String? get() = (value["status"] as? JsonValue.Text)?.value
    val error: String? get() = (value["error"] as? JsonValue.Text)?.value
    val createdAt: Long? get() = (value["createdAt"] as? JsonValue.Number)?.asLongOrNull()
    val usage: JsonValue? get() = value["usage"]
    val metadata: JsonValue? get() = value["meta"]
}

interface ChatRepository {
    /** All rows come from one database read snapshot. */
    suspend fun load(sessionId: String): ChatConversation?

    /** Atomically upserts the supplied rows and retains all other rows. */
    suspend fun save(conversation: ChatConversation)
}

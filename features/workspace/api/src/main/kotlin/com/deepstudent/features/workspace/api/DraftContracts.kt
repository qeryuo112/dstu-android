package com.deepstudent.features.workspace.api

/**
 * Opaque panel-owned draft bytes. The note adapter encodes and decodes these
 * bytes with its own codec. Workspace persistence and diagnostics never inspect
 * or log the payload.
 */
class PanelDraft(
    val panelId: PanelId,
    bytes: ByteArray,
    val revision: Long,
    val updatedAtEpochMs: Long,
) {
    private val payload: ByteArray

    init {
        require(bytes.size <= MAX_BYTES) { "workspace draft exceeds limit" }
        require(revision >= 0) { "workspace draft revision must be non-negative" }
        payload = bytes.copyOf()
    }

    val size: Int get() = payload.size

    val bytes: ByteArray get() = payload.copyOf()

    fun copyBytes(): ByteArray = payload.copyOf()

    override fun equals(other: Any?): Boolean =
        other is PanelDraft &&
            panelId == other.panelId &&
            revision == other.revision &&
            updatedAtEpochMs == other.updatedAtEpochMs &&
            payload.contentEquals(other.payload)

    override fun hashCode(): Int {
        var result = panelId.hashCode()
        result = 31 * result + revision.hashCode()
        result = 31 * result + updatedAtEpochMs.hashCode()
        result = 31 * result + payload.contentHashCode()
        return result
    }

    companion object {
        const val MAX_BYTES: Int = 16 * 1024 * 1024
    }
}

interface DraftStore {
    suspend fun load(panelId: PanelId): PanelDraft?

    suspend fun save(draft: PanelDraft): PanelDraft

    suspend fun delete(panelId: PanelId): Boolean
}

/** Layout only. Draft bytes are stored independently by [DraftStore]. */
interface LayoutSnapshotStore {
    suspend fun load(): LayoutState?

    suspend fun save(layout: LayoutState)

    suspend fun clear()
}

package com.deepstudent.services.resources.api

import com.deepstudent.core.common.error.DstuError
import com.deepstudent.core.common.error.DstuException
import com.deepstudent.core.common.id.DeviceId
import com.deepstudent.core.common.id.Digests

private val sha256Pattern = Regex("[0-9a-f]{64}")
private val attachmentIdPattern = Regex("[A-Za-z0-9_-]{1,128}")
private val chatAttachmentPathPattern =
    Regex("active/chat_attachments/([A-Za-z0-9_-]{1,128})/([0-9a-f]{64})")

fun assetError(code: String, message: String): Nothing =
    throw DstuException(DstuError.validation(code, message))

data class AssetRoot(val key: String) {
    companion object {
        val ANKI_MEDIA = AssetRoot("active/anki_media")
        val CHAT_ATTACHMENTS = AssetRoot("active/chat_attachments")
        val ALL: List<AssetRoot> = listOf(ANKI_MEDIA, CHAT_ATTACHMENTS)
    }
}

@JvmInline
value class AssetKey private constructor(val value: String) {
    val root: AssetRoot
        get() = AssetRoot.ALL.first { value.startsWith("${it.key}/") }

    val relativePath: String
        get() = value.substring(root.key.length + 1)

    companion object {
        fun parse(raw: String): AssetKey {
            val root = AssetRoot.ALL.firstOrNull { raw.startsWith("${it.key}/") }
                ?: assetError("INVALID_ASSET_KEY", "asset key is outside the active portable roots")
            val relative = raw.substring(root.key.length + 1)
            if (relative.isEmpty() || raw.startsWith('/') || '\\' in raw || '\u0000' in raw) {
                assetError("INVALID_ASSET_KEY", "invalid asset key")
            }
            val segments = relative.split('/')
            if (segments.any { it.isEmpty() || it == "." || it == ".." }) {
                assetError("INVALID_ASSET_KEY", "asset key contains a traversal segment")
            }
            return AssetKey(raw)
        }
    }
}

data class AssetDigest(
    val sha256: String,
    val size: Long,
) {
    init {
        if (!sha256Pattern.matches(sha256)) {
            assetError("INVALID_ASSET_HASH", "asset hash must be 64 lowercase hex characters")
        }
        if (size < 0) assetError("INVALID_ASSET_SIZE", "asset size must be non-negative")
    }

    fun verify(bytes: ByteArray): Boolean =
        size == bytes.size.toLong() && sha256 == Digests.sha256Hex(bytes)
}

data class AssetDescriptor(
    val key: AssetKey,
    val digest: AssetDigest,
    val role: String? = null,
)

data class AssetReference(
    val kind: String,
    val owner: String,
    val field: String,
) {
    init {
        require(kind.isNotBlank() && owner.isNotBlank() && field.isNotBlank()) {
            "asset reference fields must not be blank"
        }
    }
}

data class AssetScanEntry(
    val key: AssetKey,
    val digest: AssetDigest,
    val updatedAt: String,
)

data class AssetTombstone(
    val key: AssetKey,
    val deletedAt: String,
    val size: Long? = null,
    val deviceId: DeviceId? = null,
) {
    init {
        require(deletedAt.isNotBlank()) { "tombstone deletedAt must not be blank" }
        require(size == null || size >= 0) { "tombstone size must be non-negative" }
    }

    /**
     * Fixture/transport encoding for the bigint-valued manifest entry:
     * `{"deleted_at":...,"device_id":...,"size":{"$bigint":"32"}}`.
     */
    fun manifestEntry(): com.deepstudent.core.common.json.JsonValue.Object {
        val builder = com.deepstudent.core.common.json.JsonValue.Object.Builder()
            .put("deleted_at", deletedAt)
        if (deviceId != null) builder.put("device_id", deviceId.value)
        if (size != null) {
            builder.put(
                "size",
                com.deepstudent.core.common.json.JsonValue.obj(
                    "\$bigint" to com.deepstudent.core.common.json.JsonValue.text(size.toString()),
                ),
            )
        }
        return builder.build()
    }
}

enum class AssetTransportMode(val wire: String) {
    PLAIN("plain"),
    DSBK("DSBK"),
}

@JvmInline
value class ChatAttachmentId private constructor(val value: String) {
    companion object {
        const val MAX_BYTES = 256L * 1024L * 1024L

        fun parse(raw: String): ChatAttachmentId {
            if (!attachmentIdPattern.matches(raw)) {
                assetError("INVALID_ATTACHMENT_ID", "invalid attachment identity")
            }
            return ChatAttachmentId(raw)
        }
    }
}

data class ChatAttachmentPath(
    val value: String,
    val attachmentId: ChatAttachmentId,
    val sha256: String,
) {
    companion object {
        fun build(attachmentId: ChatAttachmentId, sha256: String): ChatAttachmentPath {
            if (!sha256Pattern.matches(sha256)) {
                assetError("INVALID_ASSET_HASH", "attachment hash must be 64 lowercase hex characters")
            }
            return ChatAttachmentPath(
                value = "active/chat_attachments/${attachmentId.value}/$sha256",
                attachmentId = attachmentId,
                sha256 = sha256,
            )
        }

        fun parse(raw: String): ChatAttachmentPath {
            val match = chatAttachmentPathPattern.matchEntire(raw)
                ?: assetError("INVALID_ATTACHMENT_PATH", "untrusted attachment storage path")
            return ChatAttachmentPath(
                value = raw,
                attachmentId = ChatAttachmentId.parse(match.groupValues[1]),
                sha256 = match.groupValues[2],
            )
        }
    }
}

class ChatAttachmentBytes(bytes: ByteArray) {
    private val snapshot: ByteArray = bytes.copyOf()

    val bytes: ByteArray get() = snapshot.copyOf()

    val size: Long get() = snapshot.size.toLong()

    val sha256: String get() = Digests.sha256Hex(snapshot)

    override fun equals(other: Any?): Boolean =
        other is ChatAttachmentBytes && snapshot.contentEquals(other.snapshot)

    override fun hashCode(): Int = snapshot.contentHashCode()
}

data class ChatAttachmentPromotionRequest(
    val attachmentId: ChatAttachmentId,
    val data: ChatAttachmentBytes,
    val expectedSha256: String,
    val rowSize: Long,
    val updatedAt: String,
) {
    init {
        if (!sha256Pattern.matches(expectedSha256)) {
            assetError("INVALID_ASSET_HASH", "attachment hash must be 64 lowercase hex characters")
        }
        if (rowSize < 0) assetError("INVALID_ASSET_SIZE", "attachment row size must be non-negative")
        require(updatedAt.isNotBlank()) { "attachment update time must not be blank" }
    }
}

data class ChatAttachmentReceipt(
    val attachmentId: ChatAttachmentId,
    val storagePath: ChatAttachmentPath,
    val sha256: String,
    val size: Long,
    val reused: Boolean,
    val executable: Boolean = false,
)

data class ChatAttachmentMetadata(
    val storagePath: ChatAttachmentPath,
    val contentHash: String,
    val status: String = "ready",
) {
    init {
        require(status == "ready") { "only ready attachments are promoted" }
        require(contentHash == storagePath.sha256) { "attachment content hash differs from storage path" }
    }
}

object ChatAttachmentPolicy {
    fun validate(request: ChatAttachmentPromotionRequest): ChatAttachmentReceipt {
        val data = request.data
        if (data.size > ChatAttachmentId.MAX_BYTES) {
            assetError("ATTACHMENT_BUDGET", "attachment byte budget")
        }
        if (data.sha256 != request.expectedSha256) {
            assetError("ATTACHMENT_HASH", "attachment hash mismatch")
        }
        if (data.size != request.rowSize) {
            assetError("ATTACHMENT_SIZE", "attachment size mismatch")
        }
        val path = ChatAttachmentPath.build(request.attachmentId, request.expectedSha256)
        return ChatAttachmentReceipt(
            attachmentId = request.attachmentId,
            storagePath = path,
            sha256 = request.expectedSha256,
            size = data.size,
            reused = false,
            executable = false,
        )
    }
}

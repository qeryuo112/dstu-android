package com.deepstudent.services.resources.api

import com.deepstudent.core.common.json.JsonFields
import com.deepstudent.core.common.json.JsonValue

object AssetTombstoneCodec {
    fun decodeManifestEntry(key: AssetKey, value: JsonValue.Object): AssetTombstone {
        val deletedAt = JsonFields.string(value, "deleted_at")
        val device = JsonFields.optionalString(value, "device_id")
        val size = when (val item = value["size"]) {
            null, JsonValue.Null -> null
            is JsonValue.Object -> {
                val bigint = JsonFields.optionalString(item, "\$bigint")
                    ?: assetError("INVALID_ASSET_SIZE", "tombstone size must use explicit bigint encoding")
                bigint.toLongOrNull()
                    ?: assetError("INVALID_ASSET_SIZE", "tombstone size is outside int64")
            }
            is JsonValue.Number -> item.asLongOrNull()
                ?: assetError("INVALID_ASSET_SIZE", "tombstone size must be an integer")
            else -> assetError("INVALID_ASSET_SIZE", "tombstone size must be an integer")
        }
        return AssetTombstone(
            key = key,
            deletedAt = deletedAt,
            size = size,
            deviceId = device?.let { com.deepstudent.core.common.id.DeviceId.parse(it) },
        )
    }
}

object ChatAttachmentCodec {
    fun decodeMetadata(value: JsonValue.Object): ChatAttachmentMetadata {
        val path = ChatAttachmentPath.parse(JsonFields.string(value, "storage_path"))
        val contentHash = JsonFields.string(value, "content_hash")
        val status = JsonFields.string(value, "status")
        return ChatAttachmentMetadata(path, contentHash, status)
    }

    fun encodeMetadata(metadata: ChatAttachmentMetadata): JsonValue.Object =
        JsonValue.obj(
            "storage_path" to JsonValue.text(metadata.storagePath.value),
            "content_hash" to JsonValue.text(metadata.contentHash),
            "status" to JsonValue.text(metadata.status),
        )

    fun decodeReceipt(value: JsonValue.Object): ChatAttachmentReceipt {
        val attachmentId = ChatAttachmentId.parse(JsonFields.string(value, "attachmentId"))
        val path = ChatAttachmentPath.parse(JsonFields.string(value, "storagePath"))
        if (attachmentId != path.attachmentId) {
            assetError("ATTACHMENT_ID_MISMATCH", "attachmentId must match the storage path owner")
        }
        val sha256 = JsonFields.string(value, "sha256")
        if (sha256 != path.sha256) {
            assetError("ATTACHMENT_HASH_MISMATCH", "sha256 must match the storage path hash")
        }
        val executable = JsonFields.boolean(value, "executable")
        if (executable) assetError("ATTACHMENT_EXECUTABLE", "attachments are data and cannot be executable")
        val size = JsonFields.integer(value, "size")
        if (size < 0 || size > ChatAttachmentId.MAX_BYTES) {
            assetError("ATTACHMENT_SIZE_BUDGET", "attachment size is outside the byte budget")
        }
        return ChatAttachmentReceipt(
            attachmentId = attachmentId,
            storagePath = path,
            sha256 = sha256,
            size = size,
            reused = JsonFields.boolean(value, "reused"),
            executable = false,
        )
    }
}

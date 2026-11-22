package com.deepstudent.services.resources.backend.assets

import com.deepstudent.core.common.json.JsonFields
import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.services.resources.api.assets.AssetTombstoneEntry
import com.deepstudent.services.resources.api.assets.AssetTombstoneManifest
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Codec only. Publication, watermark, merge, event logs and apply belong to later sync units. */
object AssetTombstoneWireCodec {
    fun decode(bytes: ByteArray): AssetTombstoneManifest {
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        val root = JsonValue.parseObject(text)
        val entries = if (root["entries"] == null) emptyMap() else
            JsonFields.objectValue(root, "entries").asMap().mapValues { (_, raw) ->
                val entry = raw as? JsonValue.Object ?: throw IllegalArgumentException("invalid tombstone entry")
                AssetTombstoneEntry(JsonFields.string(entry, "deleted_at"), JsonFields.string(entry, "device_id"),
                    optionalUnsigned(entry["size"]))
            }
        val updatedAt = if (root["updated_at"] == null) "" else JsonFields.string(root, "updated_at")
        return AssetTombstoneManifest(entries, updatedAt)
    }

    fun encode(manifest: AssetTombstoneManifest): ByteArray {
        val entries = manifest.entries.mapValues { (_, entry) ->
            JsonValue.Object.Builder().put("deleted_at", entry.deletedAt).put("device_id", entry.deviceId)
                .apply { entry.size?.let { put("size", JsonValue.Number.raw(it.toString())) } }.build()
        }
        return JsonValue.Object.Builder().put("entries", JsonValue.Object.from(entries))
            .put("updated_at", manifest.updatedAt).build().literal().toByteArray(Charsets.UTF_8)
    }

    private fun optionalUnsigned(value: JsonValue?): ULong? {
        if (value == null || value == JsonValue.Null) return null
        val literal = (value as? JsonValue.Number)?.literal ?: throw IllegalArgumentException("invalid size")
        require(Regex("0|[1-9][0-9]*").matches(literal)) { "invalid size" }
        return literal.toULongOrNull() ?: throw IllegalArgumentException("size outside u64")
    }
}

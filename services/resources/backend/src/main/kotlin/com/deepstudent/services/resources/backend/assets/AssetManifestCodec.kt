package com.deepstudent.services.resources.backend.assets

import com.deepstudent.core.common.json.JsonFields
import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.services.resources.api.assets.AssetCipherFields
import com.deepstudent.services.resources.api.assets.AssetFileManifest
import com.deepstudent.services.resources.api.assets.AssetManifestEntry
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Desktop file-manifest JSON, not its transport, object publication or deletion protocol. */
object AssetManifestCodec {
    fun decode(bytes: ByteArray): AssetFileManifest {
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        val root = JsonValue.parseObject(text)
        val entries = JsonFields.objectValue(root, "entries").asMap().mapValues { (_, value) ->
            decodeEntry(value as? JsonValue.Object ?: throw IllegalArgumentException("invalid manifest entry"))
        }
        return AssetFileManifest(entries, defaultText(root, "updated_at"))
    }

    fun encode(manifest: AssetFileManifest): ByteArray {
        val entries = manifest.entries.mapValues { (_, entry) -> encodeEntry(entry) }
        return JsonValue.Object.Builder().put("entries", JsonValue.Object.from(entries))
            .put("updated_at", manifest.updatedAt).build().literal().toByteArray(Charsets.UTF_8)
    }

    private fun decodeEntry(value: JsonValue.Object): AssetManifestEntry = AssetManifestEntry(
        sha256 = JsonFields.string(value, "sha256"), size = unsigned(value, "size"),
        updatedAt = defaultText(value, "updated_at"), revision = unsigned(value, "revision", 0u),
        objectKey = optionalText(value, "object_key"), baseSha256 = optionalText(value, "base_sha256"),
        deviceId = optionalText(value, "device_id"), cipher = AssetCipherFields(
            sha256 = optionalText(value, "cipher_sha256"),
            size = if (value["cipher_size"] == null || value["cipher_size"] == JsonValue.Null) null
                else unsigned(value, "cipher_size")),
    )

    private fun encodeEntry(entry: AssetManifestEntry): JsonValue.Object = JsonValue.Object.Builder()
        .put("sha256", entry.sha256).put("size", number(entry.size)).put("updated_at", entry.updatedAt)
        .put("revision", number(entry.revision)).apply {
            entry.objectKey?.let { put("object_key", it) }
            entry.baseSha256?.let { put("base_sha256", it) }
            entry.deviceId?.let { put("device_id", it) }
            entry.cipher.sha256?.let { put("cipher_sha256", it) }
            entry.cipher.size?.let { put("cipher_size", number(it)) }
        }.build()

    private fun defaultText(obj: JsonValue.Object, key: String): String =
        if (obj[key] == null) "" else JsonFields.string(obj, key)
    private fun optionalText(obj: JsonValue.Object, key: String): String? = JsonFields.optionalString(obj, key)
    private fun number(value: ULong): JsonValue.Number = JsonValue.Number.raw(value.toString())
    private fun unsigned(obj: JsonValue.Object, key: String, default: ULong? = null): ULong {
        val field = obj[key] ?: return default ?: throw IllegalArgumentException("missing integer field")
        val literal = (field as? JsonValue.Number)?.literal ?: throw IllegalArgumentException("invalid integer field")
        require(Regex("0|[1-9][0-9]*").matches(literal)) { "invalid unsigned integer" }
        return literal.toULongOrNull() ?: throw IllegalArgumentException("integer outside u64")
    }
}

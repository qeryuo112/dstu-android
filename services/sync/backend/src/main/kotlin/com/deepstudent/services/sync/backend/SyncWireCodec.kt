package com.deepstudent.services.sync.backend

import com.deepstudent.core.common.id.Digests
import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.services.sync.api.SyncError

object SyncWireCodec {
    fun encodeChange(input: ByteArray): ByteArray = normalizeChange(WireValues.parse(input)).literal().toByteArray()

    fun normalizeChange(source: JsonValue.Object): JsonValue.Object = WireValues.guarded {
        val operation = normalizeOperation(source["operation"])
        val output = JsonValue.Object.Builder()
            .put("table_name", WireValues.text(source["table_name"]))
            .put("record_id", WireValues.text(source["record_id"]))
            .put("operation", operation)
            .put("data", source["data"] ?: JsonValue.Null)
            .put("changed_at", WireValues.text(source["changed_at"]))
        optionalSigned(source["change_log_id"])?.let { output.put("change_log_id", it) }
        optionalString(source["database_name"])?.let { output.put("database_name", it) }
        if (source.containsKey("suppress_change_log")) {
            output.put("suppress_change_log", source["suppress_change_log"] == JsonValue.bool(true))
        }
        optionalString(source["source_device_id"])?.let { output.put("source_device_id", it) }
        optionalUnsigned(source["source_seq"])?.let { output.put("source_seq", WireValues.number(it)) }
        output.build()
    }

    fun encodeSyncManifest(input: ByteArray): ByteArray = SyncManifestCodec.encode(WireValues.parse(input))

    fun deviceHash(id: String): String = Digests.sha256HexUtf8(id).take(16)
    fun manifestKey(id: String): String = "data_governance/manifests/${deviceHash(id)}.json"

    fun changeKey(deviceId: String, seq: ULong, version: ULong, operationId: String, v4: Boolean): String {
        if (!Regex("[A-Za-z0-9_-]{1,128}").matches(operationId) || seq > 999_999_999_999uL) {
            throw SyncFailure(SyncError.LIMIT_EXCEEDED)
        }
        val prefix = if (v4) "data_governance/v4/shards" else "data_governance/changes"
        return "$prefix/${deviceHash(deviceId)}/${seq.toString().padStart(12, '0')}-$version-$operationId.json.zst"
    }

    private fun normalizeOperation(value: JsonValue?): String = when (WireValues.text(value).uppercase(java.util.Locale.ROOT)) {
        "INSERT" -> "Insert"
        "UPDATE" -> "Update"
        "DELETE" -> "Delete"
        else -> WireValues.invalid()
    }

    private fun optionalSigned(value: JsonValue?): Long? =
        if (value == null || value == JsonValue.Null) null else WireValues.signed(value)

    private fun optionalUnsigned(value: JsonValue?): ULong? =
        if (value == null || value == JsonValue.Null) null else WireValues.unsigned(value)

    private fun optionalString(value: JsonValue?): String? = (value as? JsonValue.Text)?.value
}

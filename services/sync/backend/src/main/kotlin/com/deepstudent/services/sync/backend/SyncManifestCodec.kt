package com.deepstudent.services.sync.backend

import com.deepstudent.core.common.json.JsonValue

object SyncManifestCodec {
    fun decode(bytes: ByteArray): JsonValue.Object = normalize(WireValues.parse(bytes))
    fun encode(source: JsonValue.Object): ByteArray = WirePretty.encode(normalize(source))

    private fun normalize(source: JsonValue.Object): JsonValue.Object {
        val status = WireValues.text(source["status"])
        if (status !in setOf("Complete", "Partial", "Failed")) WireValues.invalid()
        val output = JsonValue.Object.Builder()
            .put("sync_transaction_id", WireValues.text(source["sync_transaction_id"]))
            .put("databases", databases(WireValues.obj(source["databases"])))
            .put("status", status)
            .put("created_at", WireValues.text(source["created_at"]))
            .put("device_id", WireValues.text(source["device_id"]))
            .put("format_version", WireValues.number(source["format_version"]?.let {
                WireValues.unsigned(it, UInt.MAX_VALUE.toULong())
            } ?: 2uL))
            .put("published_max_seq", WireValues.number(source["published_max_seq"]?.let(WireValues::unsigned) ?: 0uL))
            .put("cursors", unsignedMap(source["cursors"]))
            .put("superseded_by", WireValues.optionalText(source["superseded_by"])?.let(JsonValue::text) ?: JsonValue.Null)
            .put("snapshot_seen", stringMap(source["snapshot_seen"]))
        return output.build()
    }

    private fun databases(source: JsonValue.Object): JsonValue.Object {
        val output = JsonValue.Object.Builder()
        for ((name, value) in source.asMap()) {
            val database = WireValues.obj(value)
            output.put(name, JsonValue.Object.Builder()
                .put("schema_version", WireValues.number(WireValues.unsigned(database["schema_version"], UInt.MAX_VALUE.toULong())))
                .put("data_version", WireValues.number(WireValues.unsigned(database["data_version"])))
                .put("checksum", WireValues.text(database["checksum"]))
                .put("last_updated_at", WireValues.optionalText(database["last_updated_at"])?.let(JsonValue::text) ?: JsonValue.Null)
                .build())
        }
        return output.build()
    }

    internal fun unsignedMap(value: JsonValue?): JsonValue.Object {
        val output = JsonValue.Object.Builder()
        if (value != null) for ((key, item) in WireValues.obj(value).asMap()) output.put(key, WireValues.number(WireValues.unsigned(item)))
        return output.build()
    }

    private fun stringMap(value: JsonValue?): JsonValue.Object {
        val output = JsonValue.Object.Builder()
        if (value != null) for ((key, item) in WireValues.obj(value).asMap()) output.put(key, WireValues.text(item))
        return output.build()
    }
}

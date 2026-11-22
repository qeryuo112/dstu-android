package com.deepstudent.services.sync.backend

import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.services.sync.api.SyncError

object SyncFormatCodec {
    fun decode(bytes: ByteArray?): JsonValue.Object = normalize(bytes?.let(WireValues::parse) ?: JsonValue.Object.EMPTY)
    fun encode(value: JsonValue.Object): ByteArray = WirePretty.encode(normalize(value))

    fun requireCompatible(value: JsonValue.Object) {
        val version = WireValues.unsigned(value["format_version"])
        val minimum = WireValues.unsigned(value["min_client"])
        if (version !in 3uL..4uL || minimum !in 3uL..4uL || minimum > version) {
            throw SyncFailure(SyncError.INCOMPATIBLE_FORMAT)
        }
    }

    fun minimumReader(value: JsonValue.Object): UInt = WireValues.unsigned(value["min_client"]).toUInt()

    private fun normalize(source: JsonValue.Object): JsonValue.Object {
        if (source.containsKey("min_client") && source.containsKey("min_reader_version")) WireValues.invalid()
        val minimum = source["min_client"] ?: source["min_reader_version"]
        val features = source["features"]?.let(WireValues::array).orEmpty().map { JsonValue.text(WireValues.text(it)) }
        val output = JsonValue.Object.Builder()
            .put("format_version", WireValues.number(source["format_version"]?.let {
                WireValues.unsigned(it, UInt.MAX_VALUE.toULong())
            } ?: 3uL))
            .put("min_client", WireValues.number(minimum?.let { WireValues.unsigned(it, UInt.MAX_VALUE.toULong()) } ?: 3uL))
            .put("features", JsonValue.array(features))
        appendExtensions(output, source)
        return output.build()
    }

    private fun appendExtensions(output: JsonValue.Object.Builder, source: JsonValue.Object) {
        source["checkpoint"]?.takeUnless { it == JsonValue.Null }?.let { output.put("checkpoint", checkpoint(WireValues.obj(it))) }
        source["compatibility_lease"]?.takeUnless { it == JsonValue.Null }?.let { output.put("compatibility_lease", lease(WireValues.obj(it))) }
    }

    private fun checkpoint(source: JsonValue.Object): JsonValue.Object {
        val output = JsonValue.Object.Builder()
            .put("generation", WireValues.number(source["generation"]?.let(WireValues::unsigned) ?: 0uL))
            .put("shard_cursors", SyncManifestCodec.unsignedMap(source["shard_cursors"]))
            .put("complete", WireValues.optionalBool(source["complete"]))
        WireValues.optionalText(source["compaction_marker_key"])?.let { output.put("compaction_marker_key", it) }
        return output.put("full_listing_verified", WireValues.optionalBool(source["full_listing_verified"])).build()
    }

    private fun lease(source: JsonValue.Object): JsonValue.Object = JsonValue.Object.Builder()
        .put("holder_device_id", source["holder_device_id"]?.let(WireValues::text) ?: "")
        .put("operation_id", source["operation_id"]?.let(WireValues::text) ?: "")
        .put("expires_at", source["expires_at"]?.let(WireValues::text) ?: "")
        .put("min_writer_version", WireValues.number(source["min_writer_version"]?.let {
            WireValues.unsigned(it, UInt.MAX_VALUE.toULong())
        } ?: 0uL)).build()
}

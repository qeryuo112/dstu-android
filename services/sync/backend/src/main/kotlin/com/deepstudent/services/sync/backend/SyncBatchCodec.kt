package com.deepstudent.services.sync.backend

import com.deepstudent.core.common.json.JsonValue

class SyncBatch(
    val changes: List<JsonValue.Object>,
    val deviceId: String,
    val formatVersion: UInt,
    val sourceSeq: ULong,
    val sourceDeviceId: String,
    val operationId: String?,
) {
    override fun toString(): String = "SyncBatch(count=${changes.size},format=$formatVersion)"
}

object SyncBatchCodec {
    fun decode(bytes: ByteArray): SyncBatch {
        val root = WireValues.parse(bytes)
        val changes = WireValues.array(root["changes"]).map { SyncWireCodec.normalizeChange(WireValues.obj(it)) }
        WireValues.unsigned(root["total_count"])
        return SyncBatch(
            changes,
            WireValues.text(root["device_id"]),
            root["format_version"]?.let { WireValues.unsigned(it, UInt.MAX_VALUE.toULong()).toUInt() } ?: 2u,
            root["source_seq"]?.let(WireValues::unsigned) ?: 0uL,
            root["source_device_id"]?.let(WireValues::text) ?: "",
            WireValues.optionalText(root["operation_id"]),
        )
    }

    fun encode(batch: SyncBatch): ByteArray = JsonValue.Object.Builder()
        .put("changes", JsonValue.array(batch.changes.map(SyncWireCodec::normalizeChange)))
        .put("total_count", batch.changes.size)
        .put("device_id", batch.deviceId)
        .put("format_version", WireValues.number(batch.formatVersion.toULong()))
        .put("source_seq", WireValues.number(batch.sourceSeq))
        .put("source_device_id", batch.sourceDeviceId)
        .put("operation_id", batch.operationId?.let(JsonValue::text) ?: JsonValue.Null)
        .build().literal().toByteArray()
}

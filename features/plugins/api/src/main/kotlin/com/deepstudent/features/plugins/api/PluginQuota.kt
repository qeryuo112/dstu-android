package com.deepstudent.features.plugins.api

import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.core.common.text.Utf8Text

data class PluginOwnerUsage(
    val records: Int,
    val bytes: Long,
) {
    init {
        if (records < 0 || bytes < 0) pluginError("INVALID_QUOTA", "plugin owner usage must be non-negative")
    }
}

data class PluginQuotaCheck(
    val records: Long,
    val bytes: Long,
    val allowed: Boolean,
    val stateValid: Boolean = true,
)

object PluginQuotaPolicy {
    fun checkPut(
        current: PluginOwnerUsage,
        previousLiveBytes: Long?,
        newRecordBytes: Long,
        tombstone: Boolean,
    ): PluginQuotaCheck {
        if (!stateIsConsistent(current, previousLiveBytes, newRecordBytes)) {
            return invalid(current)
        }
        return try {
            val records: Long
            val bytes: Long
            if (previousLiveBytes != null) {
                records = if (tombstone) current.records.toLong() - 1 else current.records.toLong()
                val released = current.bytes - previousLiveBytes
                bytes = if (tombstone) released else Math.addExact(released, newRecordBytes)
            } else {
                records = if (tombstone) current.records.toLong() else Math.addExact(current.records.toLong(), 1L)
                bytes = if (tombstone) current.bytes else Math.addExact(current.bytes, newRecordBytes)
            }
            positiveCheck(records, bytes)
        } catch (failure: ArithmeticException) {
            invalid(current)
        }
    }

    fun recordBytes(dataJson: String, unknownJson: String): Long =
        Utf8Text.byteLength(dataJson).toLong() + Utf8Text.byteLength(unknownJson).toLong()

    fun validateRecord(data: JsonValue, unknown: PluginUnknownFields): Long {
        if (data.containerDepth() > PluginDataContract.MAX_DEPTH) {
            pluginError("DEPTH_EXCEEDED", "plugin record data depth exceeds ${PluginDataContract.MAX_DEPTH}")
        }
        val dataJson = data.canonical()
        val unknownJson = unknown.canonical()
        if (recordBytes(dataJson, unknownJson) > PluginDataContract.MAX_RECORD_BYTES) {
            pluginError("RECORD_TOO_LARGE", "plugin record exceeds ${PluginDataContract.MAX_RECORD_BYTES} bytes")
        }
        val unknownValue = JsonValue.parseObject(unknownJson, "unknown_json")
        if (unknownValue.containerDepth() > PluginDataContract.MAX_DEPTH) {
            pluginError("DEPTH_EXCEEDED", "plugin record unknown depth exceeds ${PluginDataContract.MAX_DEPTH}")
        }
        return recordBytes(dataJson, unknownJson)
    }

    private fun stateIsConsistent(
        current: PluginOwnerUsage,
        previousLiveBytes: Long?,
        newRecordBytes: Long,
    ): Boolean {
        if (newRecordBytes < 0) return false
        if (previousLiveBytes == null) return true
        if (previousLiveBytes < 0 || previousLiveBytes > current.bytes) return false
        return current.records > 0
    }

    private fun positiveCheck(records: Long, bytes: Long): PluginQuotaCheck {
        if (records < 0 || bytes < 0) return invalid(records, bytes)
        val allowed = records <= PluginDataContract.MAX_OWNER_RECORDS &&
            bytes <= PluginDataContract.MAX_OWNER_BYTES
        return PluginQuotaCheck(records = records, bytes = bytes, allowed = allowed, stateValid = true)
    }

    private fun invalid(current: PluginOwnerUsage): PluginQuotaCheck =
        invalid(current.records.toLong(), current.bytes)

    private fun invalid(records: Long, bytes: Long): PluginQuotaCheck =
        PluginQuotaCheck(
            records = records,
            bytes = bytes,
            allowed = false,
            stateValid = false,
        )
}

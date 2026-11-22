package com.deepstudent.features.plugins.api

import com.deepstudent.core.common.json.JsonFields
import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.core.common.number.Int64Bounds
import java.util.Collections

data class UnknownFieldEnvelope(
    val value: JsonValue,
    val hlc: String,
    val dev: String,
    val seq: Long,
)

class PluginUnknownFields(fields: Map<String, UnknownFieldEnvelope> = emptyMap()) {
    private val snapshot: Map<String, UnknownFieldEnvelope> =
        Collections.unmodifiableMap(LinkedHashMap(fields))

    val fields: Map<String, UnknownFieldEnvelope> get() = snapshot

    fun canonical(): String {
        val builder = JsonValue.Object.Builder()
        for ((key, envelope) in snapshot) {
            builder.put(
                key,
                JsonValue.obj(
                    "value" to envelope.value,
                    "hlc" to JsonValue.text(envelope.hlc),
                    "dev" to JsonValue.text(envelope.dev),
                    "seq" to JsonValue.integer(envelope.seq),
                ),
            )
        }
        return builder.build().canonical()
    }

    fun mergedWith(incoming: PluginUnknownFields): PluginUnknownFields =
        PluginUnknownFields(LinkedHashMap(snapshot).apply { putAll(incoming.fields) })

    /**
     * Removes envelopes whose value is byte-for-byte equal (canonical JSON) to
     * a field that the incoming payload now owns.
     */
    fun pruneCovered(incomingData: JsonValue): PluginUnknownFields {
        val data = incomingData as? JsonValue.Object ?: return this
        val remaining = LinkedHashMap(snapshot)
        for (key in data.keys()) {
            val envelope = remaining[key] ?: continue
            if (envelope.value.canonical() == (data[key] ?: JsonValue.Null).canonical()) {
                remaining.remove(key)
            }
        }
        return PluginUnknownFields(remaining)
    }

    override fun equals(other: Any?): Boolean =
        other is PluginUnknownFields && snapshot == other.snapshot

    override fun hashCode(): Int = snapshot.hashCode()

    companion object {
        val EMPTY: PluginUnknownFields = PluginUnknownFields()

        fun decode(text: String): PluginUnknownFields {
            val objectValue = JsonValue.parseObject(text, "unknown_json")
            val fields = LinkedHashMap<String, UnknownFieldEnvelope>()
            for (key in objectValue.keys()) {
                val envelope = objectValue[key] as? JsonValue.Object
                    ?: pluginError("INVALID_UNKNOWN", "unknown_json.$key is not a versioned field envelope")
                if (!envelope.containsKey("value")) {
                    pluginError("INVALID_UNKNOWN", "unknown_json.$key is missing value")
                }
                val hlc = (envelope["hlc"] as? JsonValue.Text)?.value
                    ?: pluginError("INVALID_UNKNOWN", "unknown_json.$key.hlc must be text")
                val dev = (envelope["dev"] as? JsonValue.Text)?.value
                    ?: pluginError("INVALID_UNKNOWN", "unknown_json.$key.dev must be text")
                val seq = JsonFields.optionalInteger(envelope, "seq")
                    ?: pluginError("INVALID_UNKNOWN", "unknown_json.$key.seq must be a safe integer")
                Int64Bounds.requireJsonSafe(seq, "unknown_json.$key.seq")
                fields[key] = UnknownFieldEnvelope(
                    value = envelope["value"] ?: JsonValue.Null,
                    hlc = hlc,
                    dev = dev,
                    seq = seq,
                )
            }
            return PluginUnknownFields(fields)
        }

        fun apply(
            existing: PluginUnknownFields,
            incomingData: JsonValue,
            incomingUnknown: PluginUnknownFields,
        ): PluginUnknownFields =
            existing.mergedWith(incomingUnknown).pruneCovered(incomingData)
    }
}

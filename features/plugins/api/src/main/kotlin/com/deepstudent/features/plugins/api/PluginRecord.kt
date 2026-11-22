package com.deepstudent.features.plugins.api

import com.deepstudent.core.common.json.JsonFields
import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.core.common.text.Utf8Text

data class PluginRecordState(
    val dataJson: String,
    val unknownJson: String,
    val deletedAt: String?,
    val deviceId: PluginDeviceId,
    val localVersion: Long,
    val updatedAt: String,
) {
    init {
        if (localVersion <= 0) pluginError("INVALID_LOCAL_VERSION", "local_version must be positive")
        if (updatedAt.isEmpty()) pluginError("INVALID_UPDATED_AT", "updated_at must not be empty")
        if (deletedAt != null && deletedAt.isEmpty()) {
            pluginError("INVALID_DELETED_AT", "deleted_at must be empty or non-empty text")
        }
        val data = JsonValue.parse(dataJson)
        if (data.containerDepth() > PluginDataContract.MAX_DEPTH) {
            pluginError("DEPTH_EXCEEDED", "plugin record data_json depth exceeds ${PluginDataContract.MAX_DEPTH}")
        }
        PluginUnknownFields.decode(unknownJson)
        if (recordBytes(dataJson, unknownJson) > PluginDataContract.MAX_RECORD_BYTES) {
            pluginError("RECORD_TOO_LARGE", "plugin record exceeds ${PluginDataContract.MAX_RECORD_BYTES} bytes")
        }
        if (unknownJsonObjectDepth(unknownJson) > PluginDataContract.MAX_DEPTH) {
            pluginError("DEPTH_EXCEEDED", "plugin record unknown_json depth exceeds ${PluginDataContract.MAX_DEPTH}")
        }
    }

    val data: JsonValue get() = JsonValue.parse(dataJson)

    val unknown: PluginUnknownFields get() = PluginUnknownFields.decode(unknownJson)

    val recordBytes: Long get() = recordBytes(dataJson, unknownJson)

    companion object {
        fun recordBytes(dataJson: String, unknownJson: String): Long =
            Utf8Text.byteLength(dataJson).toLong() + Utf8Text.byteLength(unknownJson).toLong()

        private fun unknownJsonObjectDepth(text: String): Int =
            JsonValue.parseObject(text, "unknown_json").containerDepth()
    }
}

data class PluginRecord(
    val ownerId: PluginOwnerId,
    val namespace: PluginNamespace,
    val recordId: PluginRecordId,
    val packageVersion: PluginPackageVersion,
    val sourceHash: PluginSourceHash,
    val schemaVersion: Int,
    val minWriterSchema: Int,
    val state: PluginRecordState,
) {
    val id: String get() = pluginLogicalId(ownerId, namespace, recordId)
    val ownerKind: String get() = ownerId.ownerKind

    init {
        if (schemaVersion !in 1..PluginDataContract.MAX_SCHEMA_VERSION) {
            pluginError("INVALID_SCHEMA_VERSION", "schema_version must be a positive 32-bit integer")
        }
        if (minWriterSchema !in 1..schemaVersion) {
            pluginError("INVALID_MIN_WRITER", "min_writer_schema must be between 1 and schema_version")
        }
    }

    val dataJson: String get() = state.dataJson
    val unknownJson: String get() = state.unknownJson
    val deletedAt: String? get() = state.deletedAt
    val deviceId: PluginDeviceId get() = state.deviceId
    val localVersion: Long get() = state.localVersion
    val updatedAt: String get() = state.updatedAt
    val data: JsonValue get() = JsonValue.parse(dataJson)
    val unknown: PluginUnknownFields get() = PluginUnknownFields.decode(unknownJson)
    val recordBytes: Long get() = recordBytes(dataJson, unknownJson)

    companion object {
        fun recordBytes(dataJson: String, unknownJson: String): Long =
            PluginRecordState.recordBytes(dataJson, unknownJson)
    }
}

data class PluginApplyContext(
    val existing: PluginRecord?,
    val localSchemaVersion: Int? = existing?.schemaVersion,
)

object PluginRecordCodec {
    fun decode(value: JsonValue.Object): PluginRecord {
        val ownerId = PluginOwnerId.parse(JsonFields.string(value, "owner_id"))
        val ownerKind = JsonFields.string(value, "owner_kind")
        if (ownerKind != ownerId.ownerKind) {
            pluginError("INVALID_OWNER_KIND", "owner_kind must match owner_id")
        }
        val namespace = PluginNamespace.parse(JsonFields.string(value, "namespace"))
        val recordId = PluginRecordId.parse(JsonFields.string(value, "record_id"))
        val identity = pluginLogicalId(ownerId, namespace, recordId)
        if (value.containsKey("id") && JsonFields.string(value, "id") != identity) {
            pluginError("INVALID_LOGICAL_ID", "id must equal canonical [owner_id, namespace, record_id]")
        }
        val packageVersion = PluginPackageVersion.parse(JsonFields.string(value, "package_version"))
        val sourceHash = PluginSourceHash.parse(JsonFields.string(value, "source_hash"))
        val schemaVersion = intValue(value, "schema_version")
        val minWriter = intValue(value, "min_writer_schema")
        val dataJson = JsonFields.string(value, "data_json")
        val unknownJson = JsonFields.string(value, "unknown_json")
        val deletedAt = JsonFields.optionalString(value, "deleted_at")
        val deviceId = PluginDeviceId.parse(JsonFields.string(value, "device_id"))
        val localVersion = JsonFields.integer(value, "local_version")
        val updatedAt = JsonFields.string(value, "updated_at")
        return PluginRecord(
            ownerId = ownerId,
            namespace = namespace,
            recordId = recordId,
            packageVersion = packageVersion,
            sourceHash = sourceHash,
            schemaVersion = schemaVersion,
            minWriterSchema = minWriter,
            state = PluginRecordState(
                dataJson = dataJson,
                unknownJson = unknownJson,
                deletedAt = deletedAt,
                deviceId = deviceId,
                localVersion = localVersion,
                updatedAt = updatedAt,
            ),
        )
    }

    fun decode(text: String): PluginRecord = decode(JsonValue.parseObject(text, "plugin record"))

    fun encode(record: PluginRecord): String = encodeObject(record).literal()

    fun encodeObject(record: PluginRecord): JsonValue.Object {
        val builder = JsonValue.Object.Builder()
            .put("id", record.id)
            .put("owner_id", record.ownerId.value)
            .put("owner_kind", record.ownerKind)
            .put("namespace", record.namespace.value)
            .put("record_id", record.recordId.value)
            .put("package_version", record.packageVersion.value)
            .put("source_hash", record.sourceHash.value)
            .put("schema_version", record.schemaVersion)
            .put("min_writer_schema", record.minWriterSchema)
            .put("data_json", record.dataJson)
            .put("unknown_json", record.unknownJson)
            .put("deleted_at", record.deletedAt?.let(JsonValue::text) ?: JsonValue.Null)
            .put("device_id", record.deviceId.value)
            .put("local_version", record.localVersion)
            .put("updated_at", record.updatedAt)
        return builder.build()
    }

    private fun intValue(value: JsonValue.Object, key: String): Int {
        val number = JsonFields.integer(value, key)
        if (number < Int.MIN_VALUE || number > Int.MAX_VALUE) {
            pluginError("INVALID_SCHEMA_VERSION", "$key is outside the 32-bit integer domain")
        }
        return number.toInt()
    }
}

object PluginApplyValidator {
    fun validate(record: PluginRecord, context: PluginApplyContext) {
        context.existing?.let { existing ->
            if (existing.schemaVersion > record.schemaVersion) {
                pluginError("SCHEMA_DOWNGRADE", "remote plugin record is older than the local schema")
            }
            if (record.minWriterSchema < existing.minWriterSchema) {
                pluginError("MIN_WRITER_DOWNGRADE", "min_writer_schema cannot decrease")
            }
        }
        context.localSchemaVersion?.let { local ->
            if (local > record.schemaVersion) {
                pluginError("SCHEMA_DOWNGRADE", "remote plugin record is older than the local schema")
            }
        }
        if (record.schemaVersion < record.minWriterSchema) {
            pluginError("MIN_WRITER_DOWNGRADE", "writer schema is below the record minimum")
        }
    }

    fun mergeUnknown(
        existing: PluginRecord?,
        incomingData: JsonValue,
        incomingUnknown: PluginUnknownFields,
    ): PluginUnknownFields =
        PluginUnknownFields.apply(
            existing?.unknown ?: PluginUnknownFields.EMPTY,
            incomingData,
            incomingUnknown,
        )
}

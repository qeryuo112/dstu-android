package com.deepstudent.features.plugins.api

import com.deepstudent.core.common.error.DstuError
import com.deepstudent.core.common.error.DstuException
import com.deepstudent.core.common.json.JsonValue

object PluginDataContract {
    const val TABLE = "portable_plugin_records"
    const val MIGRATION_VERSION = 20261117L
    const val MIGRATION_FILE = "vfs/V20261117__plugin_skill_data_contract.sql"
    const val MIGRATION_SHA256 = "e0c9ba13527bf4ae0d1409dadc890b6af63a4cc86017b8b3b2a051289742ee38"
    const val MAX_DEPTH = 16
    const val MAX_RECORD_BYTES = 65_536L
    const val MAX_OWNER_RECORDS = 2_000
    const val MAX_OWNER_BYTES = 5_242_880L
    const val MAX_SCHEMA_VERSION = 2_147_483_647

    val ROW_FIELDS: List<String> = listOf(
        "owner_id",
        "owner_kind",
        "namespace",
        "record_id",
        "package_version",
        "source_hash",
        "schema_version",
        "min_writer_schema",
        "data_json",
        "unknown_json",
        "deleted_at",
        "device_id",
        "local_version",
        "updated_at",
    )
}

private val ownerPattern = Regex("(plugin|skill)\\.[A-Za-z0-9_.-]{1,121}")
private val namespacePattern = Regex("[A-Za-z0-9_.-]{1,128}")
private val sourceHashPattern = Regex("[0-9a-f]{64}")

fun pluginError(code: String, message: String): Nothing =
    throw DstuException(DstuError.validation(code, message))

@JvmInline
value class PluginOwnerId private constructor(val value: String) {
    val ownerKind: String get() = value.substringBefore('.')

    companion object {
        fun parse(raw: String): PluginOwnerId {
            if (raw.length > 128 || !ownerPattern.matches(raw)) {
                pluginError("INVALID_PLUGIN_OWNER", "owner_id must be plugin.<id> or skill.<id>")
            }
            return PluginOwnerId(raw)
        }
    }
}

@JvmInline
value class PluginNamespace private constructor(val value: String) {
    companion object {
        fun parse(raw: String): PluginNamespace {
            if (!namespacePattern.matches(raw) || ".." in raw) {
                pluginError("INVALID_NAMESPACE", "namespace must be a safe identifier without dot-dot")
            }
            return PluginNamespace(raw)
        }
    }
}

@JvmInline
value class PluginRecordId private constructor(val value: String) {
    companion object {
        fun parse(raw: String): PluginRecordId {
            if (raw.length !in 1..512 || '\u0000' in raw) {
                pluginError("INVALID_RECORD_ID", "record_id must be 1-512 characters without NUL")
            }
            return PluginRecordId(raw)
        }
    }
}

@JvmInline
value class PluginSourceHash private constructor(val value: String) {
    companion object {
        fun parse(raw: String): PluginSourceHash {
            if (!sourceHashPattern.matches(raw)) {
                pluginError("INVALID_SOURCE_HASH", "source_hash must be 64 lowercase hex characters")
            }
            return PluginSourceHash(raw)
        }
    }
}

@JvmInline
value class PluginPackageVersion private constructor(val value: String) {
    companion object {
        fun parse(raw: String): PluginPackageVersion {
            if (raw.length !in 1..128 || '\u0000' in raw) {
                pluginError("INVALID_PACKAGE_VERSION", "package_version must be 1-128 characters without NUL")
            }
            return PluginPackageVersion(raw)
        }
    }
}

@JvmInline
value class PluginDeviceId private constructor(val value: String) {
    companion object {
        fun parse(raw: String): PluginDeviceId {
            if (raw.length !in 1..256 || '\u0000' in raw) {
                pluginError("INVALID_PLUGIN_DEVICE", "device_id must be 1-256 characters without NUL")
            }
            return PluginDeviceId(raw)
        }
    }
}

data class PluginCapability(
    val ownerId: PluginOwnerId,
    val packageVersion: PluginPackageVersion,
    val sourceHash: PluginSourceHash,
    val schemaVersion: Int,
    val deviceId: PluginDeviceId,
) {
    init {
        if (schemaVersion !in 1..PluginDataContract.MAX_SCHEMA_VERSION) {
            pluginError("INVALID_SCHEMA_VERSION", "schema_version must be a positive 32-bit integer")
        }
    }
}

fun pluginLogicalId(
    ownerId: PluginOwnerId,
    namespace: PluginNamespace,
    recordId: PluginRecordId,
): String = JsonValue.Array(
    listOf(
        JsonValue.Text(ownerId.value),
        JsonValue.Text(namespace.value),
        JsonValue.Text(recordId.value),
    ),
).literal()

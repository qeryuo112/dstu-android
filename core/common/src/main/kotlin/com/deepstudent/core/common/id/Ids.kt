package com.deepstudent.core.common.id

import com.deepstudent.core.common.error.DstuError
import com.deepstudent.core.common.error.DstuException

private val safeIdCharacters = Regex("[A-Za-z0-9._:-]+")
private val deviceIdCharacters = Regex("[A-Za-z0-9._-]+")

private fun validateId(raw: String, label: String, maxBytes: Int = 128): String {
    val bytes = raw.toByteArray(Charsets.UTF_8)
    if (bytes.isEmpty() || bytes.size > maxBytes || '\u0000' in raw || !safeIdCharacters.matches(raw)) {
        throw DstuException(DstuError.validation("INVALID_$label", "invalid $label"))
    }
    return raw
}

@JvmInline
value class DeviceId private constructor(val value: String) {
    companion object {
        const val MAX_BYTES = 128
        const val SHORT_HASH_LENGTH = 16

        fun parse(raw: String): DeviceId {
            val bytes = raw.toByteArray(Charsets.UTF_8)
            if (bytes.isEmpty() || bytes.size > MAX_BYTES || '\u0000' in raw ||
                raw == "." || raw == ".." || !deviceIdCharacters.matches(raw)
            ) {
                throw DstuException(DstuError.validation("INVALID_DEVICE_ID", "invalid DEVICE_ID"))
            }
            return DeviceId(raw)
        }
    }
}

@JvmInline
value class TaskId private constructor(val value: String) {
    companion object {
        fun parse(raw: String): TaskId = TaskId(validateId(raw, "TASK_ID"))
    }
}

@JvmInline
value class CorrelationId private constructor(val value: String) {
    companion object {
        fun parse(raw: String): CorrelationId = CorrelationId(validateId(raw, "CORRELATION_ID"))
    }
}

@JvmInline
value class ResourceId private constructor(val value: String) {
    companion object {
        fun parse(raw: String): ResourceId = ResourceId(validateId(raw, "RESOURCE_ID", 512))
    }
}

@JvmInline
value class EngineId private constructor(val value: String) {
    companion object {
        fun parse(raw: String): EngineId = EngineId(validateId(raw, "ENGINE_ID"))
    }
}

object DeviceIdentity {
    private val allowed = Regex("[A-Za-z0-9._-]+")

    /**
     * Desktop `normalizeDeviceId`: Rust trim, then accept <=128 UTF-8 bytes of
     * [A-Za-z0-9._-], excluding "." and ".."; otherwise use
     * `device-<sha256-hex>` of the UTF-8 bytes.
     */
    fun normalize(raw: String): DeviceId {
        val trimmed = RustText.trim(raw)
        val bytes = trimmed.toByteArray(Charsets.UTF_8)
        val valid = trimmed.isNotEmpty() &&
            bytes.size <= DeviceId.MAX_BYTES &&
            trimmed != "." &&
            trimmed != ".." &&
            allowed.matches(trimmed)
        return DeviceId.parse(if (valid) trimmed else "device-${Digests.sha256Hex(bytes)}")
    }

    /** Desktop `deviceIdShortHash`: first 16 hex characters of SHA-256(deviceId). */
    fun shortHash(deviceId: DeviceId): String =
        Digests.sha256HexUtf8(deviceId.value).take(DeviceId.SHORT_HASH_LENGTH)
}

package com.deepstudent.services.backup.backend

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.concurrent.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal const val CONTINUITY_MAX_BYTES = 8 * 1024 * 1024
internal const val CONTINUITY_MAX_STRING = 4096
internal const val CONTINUITY_MAX_ROWS = 50_000
internal const val JSON_SAFE_INTEGER = 9_007_199_254_740_991L
private val DECIMAL = Regex("0|[1-9][0-9]*")

internal class ContinuityInvalid(val reason: ContinuityFailure) : RuntimeException()
internal fun continuityRequire(value: Boolean, reason: ContinuityFailure = ContinuityFailure.INVALID_SHAPE) {
    if (!value) throw ContinuityInvalid(reason)
}

internal object ContinuityJson {
    fun parse(bytes: ByteArray, checkCancelled: () -> Unit): JsonObject {
        continuityRequire(bytes.size <= CONTINUITY_MAX_BYTES, ContinuityFailure.TOO_LARGE)
        val raw = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            throw ContinuityInvalid(ContinuityFailure.INVALID_JSON)
        }
        try {
            UniqueJsonKeys(raw, checkCancelled).check()
            checkCancelled()
            return Json.parseToJsonElement(raw) as? JsonObject ?: throw ContinuityInvalid(ContinuityFailure.INVALID_JSON)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IllegalArgumentException) {
            throw ContinuityInvalid(ContinuityFailure.INVALID_JSON)
        } catch (_: IllegalStateException) {
            throw ContinuityInvalid(ContinuityFailure.INVALID_JSON)
        }
    }
}

internal fun JsonObject.shape(required: Set<String>, optional: Set<String> = emptySet()) {
    continuityRequire(keys.containsAll(required) && keys.all { it in required || it in optional })
}
internal fun JsonObject.objectValue(key: String): JsonObject = this[key] as? JsonObject
    ?: throw ContinuityInvalid(ContinuityFailure.INVALID_SHAPE)
internal fun JsonObject.arrayValue(key: String): JsonArray = this[key] as? JsonArray
    ?: throw ContinuityInvalid(ContinuityFailure.INVALID_SHAPE)
internal fun JsonElement.row(): JsonObject = this as? JsonObject ?: throw ContinuityInvalid(ContinuityFailure.INVALID_STATE)
internal fun JsonObject.text(key: String): String {
    val value = this[key] as? JsonPrimitive ?: throw ContinuityInvalid(ContinuityFailure.INVALID_SHAPE)
    continuityRequire(value.isString)
    return checkedText(value.content)
}
internal fun checkedText(value: String): String {
    continuityRequire(value.length <= CONTINUITY_MAX_STRING, ContinuityFailure.TOO_LARGE)
    continuityRequire(value.isNotBlank() && validUnicode(value))
    return value
}
private fun validUnicode(value: String): Boolean {
    var index = 0
    while (index < value.length) {
        val char = value[index++]
        if (char.isISOControl() || Character.isLowSurrogate(char)) return false
        if (Character.isHighSurrogate(char)) {
            if (index >= value.length || !Character.isLowSurrogate(value[index++])) return false
        }
    }
    return true
}
internal fun JsonObject.bool(key: String): Boolean {
    val value = this[key] as? JsonPrimitive ?: throw ContinuityInvalid(ContinuityFailure.INVALID_SHAPE)
    continuityRequire(!value.isString && value.content in setOf("true", "false"))
    return value.content == "true"
}
internal fun JsonObject.integer(key: String, allowString: Boolean, positive: Boolean = false): Long {
    val value = this[key] as? JsonPrimitive ?: throw ContinuityInvalid(ContinuityFailure.INVALID_SHAPE)
    continuityRequire((allowString || !value.isString) && DECIMAL.matches(value.content))
    val result = value.content.toLongOrNull() ?: throw ContinuityInvalid(ContinuityFailure.INVALID_SHAPE)
    continuityRequire(value.isString || result <= JSON_SAFE_INTEGER)
    continuityRequire(!positive || result > 0)
    return result
}
internal fun JsonObject.optionalText(key: String) { if (containsKey(key)) text(key) }

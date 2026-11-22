package com.deepstudent.services.sync.backend

import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.services.sync.api.SyncError

internal object WireValues {
    const val MAX_BYTES = 16 * 1024 * 1024

    fun parse(bytes: ByteArray): JsonValue.Object = guarded {
        if (bytes.size > MAX_BYTES) throw SyncFailure(SyncError.LIMIT_EXCEEDED)
        JsonValue.parseObject(bytes.decodeToString(throwOnInvalidSequence = true))
    }

    fun text(value: JsonValue?): String = (value as? JsonValue.Text)?.value ?: invalid()
    fun obj(value: JsonValue?): JsonValue.Object = value as? JsonValue.Object ?: invalid()
    fun array(value: JsonValue?): List<JsonValue> = (value as? JsonValue.Array)?.values ?: invalid()

    fun optionalText(value: JsonValue?): String? = if (value == null || value == JsonValue.Null) null else text(value)

    fun unsigned(value: JsonValue?, max: ULong = ULong.MAX_VALUE): ULong {
        val number = value as? JsonValue.Number ?: invalid()
        if (!number.isIntegerLiteral()) invalid()
        val result = number.literal.toULongOrNull() ?: invalid()
        if (result > max) invalid()
        return result
    }

    fun signed(value: JsonValue?): Long {
        val number = value as? JsonValue.Number ?: invalid()
        return number.asLongOrNull() ?: invalid()
    }

    fun optionalBool(value: JsonValue?, default: Boolean = false): Boolean = when (value) {
        null -> default
        is JsonValue.Bool -> value.value
        else -> invalid()
    }

    fun number(value: ULong): JsonValue.Number = JsonValue.Number.raw(value.toString())
    fun invalid(): Nothing = throw SyncFailure(SyncError.INVALID_REPOSITORY)

    inline fun <T> guarded(block: () -> T): T = try {
        block()
    } catch (failure: SyncFailure) {
        throw failure
    } catch (_: Exception) {
        invalid()
    }
}

internal object WirePretty {
    fun encode(value: JsonValue): ByteArray = write(value, 0).toByteArray(Charsets.UTF_8)

    private fun write(value: JsonValue, depth: Int): String = when (value) {
        is JsonValue.Object -> container(value.asMap().map { (key, item) ->
            "${JsonValue.text(key).literal()}: ${write(item, depth + 1)}"
        }, "{", "}", depth)
        is JsonValue.Array -> container(value.values.map { write(it, depth + 1) }, "[", "]", depth)
        else -> value.literal()
    }

    private fun container(values: List<String>, open: String, close: String, depth: Int): String {
        if (values.isEmpty()) return open + close
        val indent = "  ".repeat(depth + 1)
        return open + "\n" + values.joinToString(",\n") { indent + it } + "\n" + "  ".repeat(depth) + close
    }
}

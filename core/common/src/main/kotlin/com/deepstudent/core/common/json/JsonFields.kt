package com.deepstudent.core.common.json

import com.deepstudent.core.common.error.DstuError
import com.deepstudent.core.common.error.DstuException

object JsonFields {
    fun string(value: JsonValue.Object, key: String, path: String = key): String {
        val field = value[key] ?: fieldError(path, "is required")
        return (field as? JsonValue.Text)?.value ?: fieldError(path, "must be a string")
    }

    fun optionalString(value: JsonValue.Object, key: String, path: String = key): String? =
        when (val field = value[key]) {
            null, JsonValue.Null -> null
            is JsonValue.Text -> field.value
            else -> fieldError(path, "must be a string or null")
        }

    fun integer(value: JsonValue.Object, key: String, path: String = key): Long {
        val field = value[key] ?: fieldError(path, "is required")
        return integerValue(field, path)
    }

    fun optionalInteger(value: JsonValue.Object, key: String, path: String = key): Long? =
        when (val field = value[key]) {
            null, JsonValue.Null -> null
            else -> integerValue(field, path)
        }

    fun boolean(value: JsonValue.Object, key: String, path: String = key): Boolean {
        val field = value[key] ?: fieldError(path, "is required")
        return (field as? JsonValue.Bool)?.value ?: fieldError(path, "must be a boolean")
    }

    fun objectValue(value: JsonValue.Object, key: String, path: String = key): JsonValue.Object {
        val field = value[key] ?: fieldError(path, "is required")
        return field as? JsonValue.Object ?: fieldError(path, "must be an object")
    }

    fun optionalObject(value: JsonValue.Object, key: String, path: String = key): JsonValue.Object? =
        when (val field = value[key]) {
            null, JsonValue.Null -> null
            is JsonValue.Object -> field
            else -> fieldError(path, "must be an object or null")
        }

    fun integerValue(value: JsonValue, path: String): Long =
        (value as? JsonValue.Number)?.asLongOrNull()
            ?: fieldError(path, "must be an integer")

    fun fieldError(path: String, reason: String): Nothing =
        throw DstuException(DstuError.validation("INVALID_JSON_FIELD", "$path $reason"))
}

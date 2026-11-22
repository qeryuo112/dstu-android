package com.deepstudent.core.common.json

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

object JsonParser {
    fun parse(text: String): JsonValue {
        val element = try {
            Json.parseToJsonElement(text)
        } catch (failure: SerializationException) {
            jsonError("INVALID_JSON", "invalid JSON")
        } catch (failure: IllegalArgumentException) {
            jsonError("INVALID_JSON", "invalid JSON")
        }
        return try {
            convert(element)
        } catch (failure: com.deepstudent.core.common.error.DstuException) {
            throw failure
        } catch (failure: IllegalArgumentException) {
            jsonError("INVALID_JSON", "invalid JSON")
        }
    }

    fun parseObject(text: String, label: String): JsonValue.Object =
        when (val value = parse(text)) {
            is JsonValue.Object -> value
            else -> jsonError("INVALID_JSON", "$label must be a JSON object")
        }

    private fun convert(element: JsonElement): JsonValue = when (element) {
        JsonNull -> JsonValue.Null
        is JsonPrimitive -> convertPrimitive(element)
        is JsonArray -> JsonValue.Array(element.map(::convert))
        is JsonObject -> {
            val builder = JsonValue.Object.Builder()
            for ((key, value) in element) builder.put(key, convert(value))
            builder.build()
        }
    }

    private fun convertPrimitive(element: JsonPrimitive): JsonValue {
        if (element.isString) return JsonValue.Text(element.content)
        return when (element.content) {
            "true" -> JsonValue.Bool(true)
            "false" -> JsonValue.Bool(false)
            "null" -> JsonValue.Null
            "NaN", "Infinity", "-Infinity" -> jsonError("INVALID_JSON", "invalid JSON")
            else -> try {
                JsonValue.Number.raw(element.content)
            } catch (failure: IllegalArgumentException) {
                jsonError("INVALID_JSON", "invalid JSON")
            }
        }
    }
}

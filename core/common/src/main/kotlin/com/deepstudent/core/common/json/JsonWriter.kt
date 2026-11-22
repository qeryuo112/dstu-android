package com.deepstudent.core.common.json

internal object JsonWriter {
    private val stringEscapes = mapOf(
        '"' to "\\\"",
        '\\' to "\\\\",
        '\b' to "\\b",
        '\u000c' to "\\f",
        '\n' to "\\n",
        '\r' to "\\r",
        '\t' to "\\t",
    )

    fun write(value: JsonValue, sortedKeys: Boolean): String {
        val output = StringBuilder()
        append(value, output, sortedKeys)
        return output.toString()
    }

    private fun append(value: JsonValue, output: StringBuilder, sortedKeys: Boolean) {
        when (value) {
            JsonValue.Null -> output.append("null")
            is JsonValue.Bool -> output.append(if (value.value) "true" else "false")
            is JsonValue.Text -> appendString(value.value, output)
            is JsonValue.Number -> output.append(value.literal)
            is JsonValue.Array -> appendArray(value, output, sortedKeys)
            is JsonValue.Object -> appendObject(value, output, sortedKeys)
        }
    }

    private fun appendArray(value: JsonValue.Array, output: StringBuilder, sortedKeys: Boolean) {
        output.append('[')
        value.values.forEachIndexed { index, item ->
            if (index > 0) output.append(',')
            append(item, output, sortedKeys)
        }
        output.append(']')
    }

    private fun appendObject(value: JsonValue.Object, output: StringBuilder, sortedKeys: Boolean) {
        output.append('{')
        val keys = value.keys().let { if (sortedKeys) it.sorted() else it }
        keys.forEachIndexed { index, key ->
            if (index > 0) output.append(',')
            appendString(key, output)
            output.append(':')
            append(value[key] ?: JsonValue.Null, output, sortedKeys)
        }
        output.append('}')
    }

    /**
     * Equivalent to ECMAScript JSON.stringify for strings: non-ASCII remains
     * literal, control characters use short escapes when available, and lone
     * surrogates are escaped so the output is well-formed UTF-16 JSON.
     */
    fun appendString(value: String, output: StringBuilder) {
        output.append('"')
        var index = 0
        while (index < value.length) {
            val character = value[index]
            if (Character.isHighSurrogate(character) &&
                index + 1 < value.length &&
                Character.isLowSurrogate(value[index + 1])
            ) {
                output.append(character)
                output.append(value[index + 1])
                index += 2
                continue
            }
            appendCharacter(character, output)
            index++
        }
        output.append('"')
    }

    private fun appendCharacter(character: Char, output: StringBuilder) {
        val escaped = stringEscapes[character]
        if (escaped != null) {
            output.append(escaped)
            return
        }
        if (character.code < 0x20 ||
            Character.isHighSurrogate(character) ||
            Character.isLowSurrogate(character)
        ) {
            appendUnicodeEscape(character.code, output)
            return
        }
        output.append(character)
    }

    private fun appendUnicodeEscape(code: Int, output: StringBuilder) {
        val digits = "0123456789abcdef"
        output.append("\\u")
        output.append(digits[(code ushr 12) and 0x0f])
        output.append(digits[(code ushr 8) and 0x0f])
        output.append(digits[(code ushr 4) and 0x0f])
        output.append(digits[code and 0x0f])
    }
}

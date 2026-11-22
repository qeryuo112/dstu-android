package com.deepstudent.core.common.number

import com.deepstudent.core.common.error.DstuError
import com.deepstudent.core.common.error.DstuException

object Int64Bounds {
    const val MIN: Long = Long.MIN_VALUE
    const val MAX: Long = Long.MAX_VALUE
    const val JSON_SAFE_MIN: Long = -9_007_199_254_740_991L
    const val JSON_SAFE_MAX: Long = 9_007_199_254_740_991L

    private val integerLiteral = Regex("-?(?:0|[1-9][0-9]*)")

    fun isJsonSafe(value: Long): Boolean = value in JSON_SAFE_MIN..JSON_SAFE_MAX

    fun requireJsonSafe(value: Long, label: String = "integer"): Long {
        if (!isJsonSafe(value)) {
            throw DstuException(
                DstuError.validation(
                    "INTEGER_NOT_JSON_SAFE",
                    "$label is outside the JSON safe integer domain: $value",
                ),
            )
        }
        return value
    }

    fun parseInt64(text: String, label: String = "integer"): Long {
        if (!integerLiteral.matches(text)) {
            throw DstuException(DstuError.validation("INVALID_INT64", "$label is not an int64 literal: $text"))
        }
        return text.toLongOrNull()
            ?: throw DstuException(DstuError.validation("INT64_OUT_OF_RANGE", "$label is outside int64: $text"))
    }

    fun parseJsonSafeInteger(text: String, label: String = "integer"): Long =
        requireJsonSafe(parseInt64(text, label), label)
}

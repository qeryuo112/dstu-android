package com.deepstudent.features.notes.data

import com.deepstudent.core.common.json.JsonValue
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

internal fun syncTimestamp(value: JsonValue?): String? = when (value) {
    is JsonValue.Text -> value.value
    is JsonValue.Number -> value.literal
    else -> null
}

internal fun syncMillis(value: String): Long? {
    val number = value.toLongOrNull()
    if (number != null) return if (number in -99_999_999_999L..99_999_999_999L) number * 1000 else number
    return runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()
        ?: runCatching { LocalDateTime.parse(value.replace(' ', 'T')).toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull()
}

internal fun syncCompare(left: String, leftDevice: String, leftContent: String, right: String, rightDevice: String, rightContent: String): Int {
    val a = syncTimeKey(left, leftContent)
    val b = syncTimeKey(right, rightContent)
    return compareValues(a.first, b.first).takeIf { it != 0 }
        ?: compareValues(a.second, b.second).takeIf { it != 0 }
        ?: syncUtf8Compare(leftDevice, rightDevice).takeIf { it != 0 }
        ?: syncUtf8Compare(a.third, b.third)
}

private fun syncTimeKey(value: String, content: String): Triple<Long, Int, String> {
    val match = Regex("^([0-9]+)-([0-9]+)$").matchEntire(value)
    if (match != null) {
        val millis = match.groupValues[1].toLongOrNull()
        val counter = match.groupValues[2].toIntOrNull()
        if (millis != null && counter != null && counter <= 65535) return Triple(millis, counter, content)
    }
    val millis = syncMillis(value) ?: return Triple(Long.MIN_VALUE, 0, "$value|$content")
    return Triple(millis, 0, content)
}

internal fun syncUtf8Compare(left: String, right: String): Int {
    val a = left.toByteArray(Charsets.UTF_8)
    val b = right.toByteArray(Charsets.UTF_8)
    for (i in 0 until minOf(a.size, b.size)) {
        val difference = (a[i].toInt() and 255) - (b[i].toInt() and 255)
        if (difference != 0) return difference
    }
    return a.size.compareTo(b.size)
}

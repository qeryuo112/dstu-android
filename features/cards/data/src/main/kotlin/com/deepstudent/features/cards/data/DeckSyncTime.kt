package com.deepstudent.features.cards.data

import java.math.BigInteger
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

private data class DeckTimeKey(val millis: Long, val counter: Int, val content: String)

internal fun deckCompare(left: String, leftDevice: String, leftContent: String, right: String, rightDevice: String, rightContent: String): Int {
    val a = deckTimeKey(left, leftContent)
    val b = deckTimeKey(right, rightContent)
    return compareValues(a.millis, b.millis).takeIf { it != 0 }
        ?: compareValues(a.counter, b.counter).takeIf { it != 0 }
        ?: deckUtf8Compare(leftDevice, rightDevice).takeIf { it != 0 }
        ?: deckUtf8Compare(a.content, b.content)
}

private fun deckTimeKey(value: String, content: String): DeckTimeKey {
    val hlc = Regex("^(\\+?[0-9]+)-(\\+?[0-9]+)$").matchEntire(value)
    if (hlc != null) {
        val millis = hlc.groupValues[1].toBigIntegerOrNull()
        val counter = hlc.groupValues[2].toIntOrNull()
        if (millis != null && millis <= UINT64_MAX && counter != null && counter <= 65535) {
            return DeckTimeKey(millis.min(LONG_MAX).toLong(), counter, content)
        }
    }
    val millis = deckTimestampMillis(value) ?: return DeckTimeKey(Long.MIN_VALUE, 0, "$value|$content")
    return DeckTimeKey(millis, 0, content)
}

internal fun deckTimestampMillis(value: String): Long? {
    val hlc = Regex("^(\\+?[0-9]+)-(\\+?[0-9]+)$").matchEntire(value)
    if (hlc != null) {
        val millis = hlc.groupValues[1].toLongOrNull()
        val counter = hlc.groupValues[2].toIntOrNull()
        return millis?.takeIf { counter != null && counter <= 65535 }
    }
    value.toLongOrNull()?.let { number ->
        val millis = when {
            number >= 100_000_000_000L -> number
            number >= 1_000_000_000L -> number * 1000
            else -> return null
        }
        return millis.takeIf { it in -8_334_601_228_800_000L..8_210_266_876_799_999L }
    }
    return runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()
        ?: runCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }.getOrNull()
        ?: runCatching { LocalDateTime.parse(value.replace(' ', 'T')).toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull()
}

private val LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE)
private val UINT64_MAX = BigInteger("18446744073709551615")

internal fun deckUtf8Compare(left: String, right: String): Int {
    val a = left.toByteArray(Charsets.UTF_8)
    val b = right.toByteArray(Charsets.UTF_8)
    for (index in 0 until minOf(a.size, b.size)) {
        val difference = (a[index].toInt() and 255) - (b[index].toInt() and 255)
        if (difference != 0) return difference
    }
    return a.size.compareTo(b.size)
}

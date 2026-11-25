package com.deepstudent.services.sync.data.apply

import java.math.BigInteger
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

private data class IncomingTime(val millis: Long, val counter: Int, val tie: String)

internal fun incomingCompare(left: String, leftDevice: String, leftTie: String, right: String, rightDevice: String, rightTie: String): Int {
    val a = incomingTime(left, leftTie)
    val b = incomingTime(right, rightTie)
    return compareValues(a.millis, b.millis).takeIf { it != 0 }
        ?: compareValues(a.counter, b.counter).takeIf { it != 0 }
        ?: incomingUtf8(leftDevice, rightDevice).takeIf { it != 0 }
        ?: incomingUtf8(a.tie, b.tie)
}

private fun incomingTime(value: String, tie: String): IncomingTime {
    incomingHlc(value, tie)?.let { return it }
    val number = value.toLongOrNull()
    val millis = if (number != null) {
        if (number in -99_999_999_999L..99_999_999_999L) number * 1000 else number
    } else {
        runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(value.replace(' ', 'T')).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching { LocalDateTime.parse(value.replace(' ', 'T')).toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull()
    }
    return if (millis == null) IncomingTime(Long.MIN_VALUE, 0, "$value|$tie") else IncomingTime(millis, 0, tie)
}

private fun incomingHlc(value: String, tie: String): IncomingTime? {
    val hlc = Regex("^(\\+?[0-9]+)-(\\+?[0-9]+)$").matchEntire(value) ?: return null
    val millis = hlc.groupValues[1].removePrefix("+").toBigIntegerOrNull() ?: return null
    val counter = hlc.groupValues[2].removePrefix("+").toIntOrNull() ?: return null
    if (millis > BigInteger("18446744073709551615") || counter > 65535) return null
    return IncomingTime(millis.min(BigInteger.valueOf(Long.MAX_VALUE)).toLong(), counter, tie)
}

private fun incomingUtf8(left: String, right: String): Int {
    val a = left.toByteArray(Charsets.UTF_8)
    val b = right.toByteArray(Charsets.UTF_8)
    for (i in 0 until minOf(a.size, b.size)) {
        val difference = (a[i].toInt() and 255) - (b[i].toInt() and 255)
        if (difference != 0) return difference
    }
    return a.size.compareTo(b.size)
}

package com.deepstudent.features.media.api

import com.deepstudent.core.common.error.DstuError
import com.deepstudent.core.common.error.DstuException
import com.deepstudent.core.common.number.Int64Bounds
import java.math.BigDecimal
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset

object MediaContract {
    const val TABLE = "portable_media_events"
    const val PRIMARY_KEY = "event_id"
    const val MIGRATION_VERSION = 20261116L
    const val MIGRATION_FILE = "vfs/V20261116__portable_media_progress.sql"
    const val MIGRATION_SHA256 = "1beeb57d996340270b2f8acfe3fe6608a7432fda5c4178aca6034abaad00d116"
    const val STUDY_SECONDS_CAP = 86_400L
    const val SAFE_MILLISECOND_MAX = Int64Bounds.JSON_SAFE_MAX
}

enum class MediaKind(val wire: String) {
    STUDY("study"),
    RESUME("resume"),
    SUBTITLE("subtitle"),
    ;

    companion object {
        fun fromWire(value: String): MediaKind =
            entries.firstOrNull { it.wire == value }
                ?: mediaError("UNKNOWN_MEDIA_KIND", "media event: unknown kind $value")
    }
}

data class MediaTimestamp(
    val canonical: String,
    val epochMillis: Long,
) {
    companion object {
        private val pattern = Regex(
            "^(\\d{4})-(\\d{2})-(\\d{2})T(\\d{2}):(\\d{2}):(\\d{2})(?:\\.(\\d{3}))?(Z|[+-]\\d{2}:\\d{2})$",
        )

        fun parse(raw: String, field: String = "observed_at"): MediaTimestamp {
            val match = pattern.matchEntire(raw)
                ?: mediaError("INVALID_MEDIA_TIME", "media event time: invalid $field")
            val groups = match.groupValues
            val date = parseDate(groups, field)
            validateClock(groups, field)
            val offsetMinutes = parseOffset(groups[8], field)
            val epochMillis = epochMillis(date, groups, offsetMinutes)
            return MediaTimestamp(
                canonical = Instant.ofEpochMilli(epochMillis).toString(),
                epochMillis = epochMillis,
            )
        }

        private fun parseDate(groups: List<String>, field: String): LocalDate =
            try {
                LocalDate.of(groups[1].toInt(), groups[2].toInt(), groups[3].toInt())
            } catch (failure: DateTimeException) {
                mediaError("INVALID_MEDIA_TIME", "media event time: invalid $field")
            }

        private fun validateClock(groups: List<String>, field: String) {
            if (groups[4].toInt() > 23 || groups[5].toInt() > 59 || groups[6].toInt() > 59) {
                mediaError("INVALID_MEDIA_TIME", "media event time: invalid $field")
            }
        }

        private fun parseOffset(zone: String, field: String): Int {
            if (zone == "Z") return 0
            val hour = zone.substring(1, 3).toInt()
            val minute = zone.substring(4, 6).toInt()
            if (hour > 23 || minute > 59) {
                mediaError("INVALID_MEDIA_TIME", "media event time: invalid offset $field")
            }
            return (hour * 60 + minute) * if (zone[0] == '-') -1 else 1
        }

        private fun epochMillis(date: LocalDate, groups: List<String>, offsetMinutes: Int): Long {
            val hour = groups[4].toInt()
            val minute = groups[5].toInt()
            val second = groups[6].toInt()
            val fraction = if (groups[7].isEmpty()) 0 else groups[7].toInt()
            val localSeconds = LocalDateTime.of(date, LocalTime.of(hour, minute, second))
                .toEpochSecond(ZoneOffset.UTC)
            return (localSeconds - offsetMinutes * 60L) * 1000L + fraction
        }
    }
}

fun validLogicalDate(raw: String): String {
    if (!Regex("\\d{4}-\\d{2}-\\d{2}").matches(raw)) {
        mediaError("INVALID_LOGICAL_DATE", "media event: logical_date must be YYYY-MM-DD")
    }
    try {
        val parsed = LocalDate.parse(raw)
        if (parsed.toString() != raw) {
            mediaError("INVALID_LOGICAL_DATE", "media event: invalid logical_date $raw")
        }
    } catch (failure: DateTimeException) {
        mediaError("INVALID_LOGICAL_DATE", "media event: invalid logical_date $raw")
    }
    return raw
}

internal fun mediaInteger(value: String, label: String, minimum: Long, maximum: Long): Long {
    val decimal = try {
        BigDecimal(value)
    } catch (failure: NumberFormatException) {
        mediaError("INVALID_MEDIA_INTEGER", "media event $label must be an integer")
    }
    if (decimal.stripTrailingZeros().scale() > 0) {
        mediaError("INVALID_MEDIA_INTEGER", "media event $label must be an integer")
    }
    val result = try {
        decimal.longValueExact()
    } catch (failure: ArithmeticException) {
        mediaError("INVALID_MEDIA_INTEGER", "media event $label must be an integer")
    }
    if (result < minimum || result > maximum) {
        mediaError("MEDIA_INTEGER_RANGE", "media event $label out of range")
    }
    return result
}

fun mediaError(code: String, message: String): Nothing =
    throw DstuException(DstuError.validation(code, message))

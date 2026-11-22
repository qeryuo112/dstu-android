package com.deepstudent.features.review.backend.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.max
import kotlin.math.round

internal object FsrsLogicalTime {
    fun dayKey(nowMs: Long, rolloverHour: Int, zone: ZoneId): String =
        logicalDate(nowMs, rolloverHour, zone).toString()

    fun daysBetween(fromMs: Long, toMs: Long, rolloverHour: Int, zone: ZoneId): Long {
        val from = logicalDate(fromMs, rolloverHour, zone)
        val to = logicalDate(toMs, rolloverHour, zone)
        val days = ChronoUnit.DAYS.between(from, to)
        return days.coerceIn(0L, 4_294_967_295L)
    }

    fun bounds(nowMs: Long, rolloverHour: Int, zone: ZoneId): Pair<Long, Long> {
        val today = logicalDate(nowMs, rolloverHour, zone)
        val start = startOfDay(today, rolloverHour, zone)
        val next = startOfDay(today.plusDays(1), rolloverHour, zone)
        return start to max(start + 1, next)
    }

    fun learningDueMs(nowMs: Long, minutes: Double, rolloverHour: Int, zone: ZoneId): Long {
        val due = nowMs + round(max(0.0, minutes) * 60_000.0).toLong()
        val nextDayStart = bounds(nowMs, rolloverHour, zone).second
        if (due < nextDayStart) return due
        return startOfDay(logicalDate(due, rolloverHour, zone), rolloverHour, zone)
    }

    private fun logicalDate(ms: Long, rolloverHour: Int, zone: ZoneId): LocalDate {
        val local = Instant.ofEpochMilli(ms).atZone(zone)
        val date = local.toLocalDate()
        return if (local.hour < rolloverHour.coerceIn(0, 23)) date.minusDays(1) else date
    }

    private fun startOfDay(date: LocalDate, rolloverHour: Int, zone: ZoneId): Long =
        date.atTime(rolloverHour.coerceIn(0, 23), 0).atZone(zone).toInstant().toEpochMilli()
}

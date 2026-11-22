package com.deepstudent.features.review.backend.domain

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

internal object FsrsFuzz {
    private const val MAX_STEP_MINUTES = 525_600.0
    private const val MAX_STEPS = 16
    private const val MINUTES_PER_DAY = 1_440.0

    fun sanitizeSteps(steps: List<Double>): List<Double> =
        steps.filter { it.isFinite() && it > 0 }
            .map { min(it, MAX_STEP_MINUTES) }
            .take(MAX_STEPS)

    fun hardDelay(steps: List<Double>, index: Int): Double {
        val safe = index.coerceIn(0, max(0, steps.size - 1))
        val current = steps[safe]
        if (index > 0) return current
        val next = steps.getOrNull(1)
        return if (next != null) (current + next) / 2 else min(current * 1.5, current + MINUTES_PER_DAY)
    }

    fun delta(interval: Double): Double {
        if (interval < 2.5) return 0.0
        val ranges = listOf(
            Triple(2.5, 7.0, 0.15),
            Triple(7.0, 20.0, 0.1),
            Triple(20.0, Double.MAX_VALUE, 0.05),
        )
        return ranges.fold(1.0) { sum, (start, end, factor) ->
            sum + factor * max(0.0, min(interval, end) - start)
        }
    }

    fun bounds(interval: Double, minimum: Int, maximum: Int): Pair<Int, Int> {
        val minValue = min(minimum, maximum)
        val value = interval.coerceIn(minValue.toDouble(), maximum.toDouble())
        val delta = delta(value)
        var lower = round(max(0.0, value - delta)).coerceIn(minValue.toDouble(), maximum.toDouble()).toInt()
        var upper = round(max(0.0, value + delta)).coerceIn(minValue.toDouble(), maximum.toDouble()).toInt()
        if (upper == lower && upper > 2 && upper < maximum) upper = lower + 1
        if (lower > upper) {
            val swap = lower
            lower = upper
            upper = swap
        }
        return lower to upper
    }

    fun withReviewFuzz(
        factor: Double?,
        interval: Double,
        minimum: Int,
        maximum: Int,
    ): Int {
        val value = if (interval.isFinite()) interval else minimum.toDouble()
        if (factor != null) {
            val (lower, upper) = bounds(value, minimum, maximum)
            val clamped = factor.coerceIn(0.0, 0.999999)
            return floor(lower + clamped * (upper - lower + 1)).toInt()
        }
        val rounded = round(max(0.0, value)).toInt()
        return rounded.coerceIn(min(minimum, maximum), maximum)
    }

    /** Anki `with_review_fuzz` deterministic factor, matching the desktop FNV-1a + splitmix64. */
    fun factorFor(cardId: String, reps: Int): Double {
        var hash = 0xcbf29ce484222325uL
        for (byte in utf8WithReplacement(cardId)) {
            hash = (hash xor byte.toUByte().toULong()) * 0x100000001b3uL
        }
        for (shift in 0..24 step 8) {
            val byte = ((reps ushr shift) and 0xFF).toUByte()
            hash = (hash xor byte.toULong()) * 0x100000001b3uL
        }
        hash = hash xor (hash shr 30)
        hash *= 0xbf58476d1ce4e5b9uL
        hash = hash xor (hash shr 27)
        hash *= 0x94d049bb133111ebuL
        hash = hash xor (hash shr 31)
        return (hash shr 40).toDouble() / 16_777_216.0
    }

    private fun utf8WithReplacement(value: String): ByteArray {
        val out = ArrayList<Byte>(value.length)
        var index = 0
        while (index < value.length) {
            val code = value[index].code
            when {
                code < 0x80 -> out.add(code.toByte())
                code < 0x800 -> {
                    out.add((0xC0 or (code shr 6)).toByte())
                    out.add((0x80 or (code and 0x3F)).toByte())
                }
                code in 0xD800..0xDBFF -> {
                    val next = value.getOrNull(index + 1)?.code
                    if (next != null && next in 0xDC00..0xDFFF) {
                        val point = 0x10000 + ((code - 0xD800) shl 10) + (next - 0xDC00)
                        addCodePoint(out, point)
                        index += 1
                    } else {
                        addCodePoint(out, 0xFFFD)
                    }
                }
                code in 0xDC00..0xDFFF -> addCodePoint(out, 0xFFFD)
                else -> {
                    out.add((0xE0 or (code shr 12)).toByte())
                    out.add((0x80 or ((code shr 6) and 0x3F)).toByte())
                    out.add((0x80 or (code and 0x3F)).toByte())
                }
            }
            index += 1
        }
        return out.toByteArray()
    }

    private fun addCodePoint(out: MutableList<Byte>, point: Int) {
        when {
            point < 0x80 -> out.add(point.toByte())
            point < 0x800 -> {
                out.add((0xC0 or (point shr 6)).toByte())
                out.add((0x80 or (point and 0x3F)).toByte())
            }
            point < 0x10000 -> {
                out.add((0xE0 or (point shr 12)).toByte())
                out.add((0x80 or ((point shr 6) and 0x3F)).toByte())
                out.add((0x80 or (point and 0x3F)).toByte())
            }
            else -> {
                out.add((0xF0 or (point shr 18)).toByte())
                out.add((0x80 or ((point shr 12) and 0x3F)).toByte())
                out.add((0x80 or ((point shr 6) and 0x3F)).toByte())
                out.add((0x80 or (point and 0x3F)).toByte())
            }
        }
    }
}

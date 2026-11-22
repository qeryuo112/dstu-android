package com.deepstudent.features.media.backend.domain

import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.core.common.text.Utf8Text
import com.deepstudent.features.media.api.MediaContract
import com.deepstudent.features.media.api.MediaDeviceTotal
import com.deepstudent.features.media.api.MediaEventRow
import com.deepstudent.features.media.api.MediaKind
import com.deepstudent.features.media.api.MediaPlanVersion
import com.deepstudent.features.media.api.MediaResume
import com.deepstudent.features.media.api.MediaSubtitle
import com.deepstudent.features.media.api.MediaSummary
import com.deepstudent.features.media.api.mediaError
import com.deepstudent.features.media.api.validLogicalDate
import java.math.BigDecimal
import java.math.BigInteger

object MediaSummaryCalculator {
    fun summarize(events: List<MediaEventRow>, resourceId: String, logicalDate: String): MediaSummary {
        validLogicalDate(logicalDate)
        val unique = LinkedHashMap<String, MediaEventRow>()
        for (event in events) {
            if (event.deletedAt == null &&
                event.resourceId == resourceId &&
                event.logicalDate == logicalDate
            ) {
                unique.putIfAbsent(event.eventId, event)
            }
        }
        val rows = unique.values.toList()
        val resume = resumeWinner(rows)
        val subtitles = subtitleWinners(rows)
        return MediaSummary(
            resourceId = resourceId,
            logicalDate = logicalDate,
            seconds = studySeconds(rows),
            resume = resume?.let {
                MediaResume(
                    positionMillis = it.positionMillis ?: 0,
                    durationMillis = it.durationMillis ?: 0,
                    eventId = it.eventId,
                    observedAt = it.observedAt.canonical,
                )
            },
            byDevice = deviceTotals(rows),
            subtitles = subtitles,
            latestPlanVersions = latestPlanVersions(subtitles),
            eventCount = rows.size,
        )
    }

    private fun studySeconds(rows: List<MediaEventRow>): Long {
        var total = BigInteger.ZERO
        for (row in rows) {
            if (row.kind == MediaKind.STUDY) total += BigInteger.valueOf(row.seconds ?: 0)
        }
        return total.min(BigInteger.valueOf(MediaContract.STUDY_SECONDS_CAP)).toLong()
    }

    private fun deviceTotals(rows: List<MediaEventRow>): List<MediaDeviceTotal> {
        val totals = LinkedHashMap<String, Pair<BigInteger, Int>>()
        for (row in rows) {
            val previous = totals[row.deviceId] ?: (BigInteger.ZERO to 0)
            val seconds = previous.first + if (row.kind == MediaKind.STUDY) {
                BigInteger.valueOf(row.seconds ?: 0)
            } else {
                BigInteger.ZERO
            }
            totals[row.deviceId] = seconds to (previous.second + 1)
        }
        return totals.entries
            .sortedWith { left, right -> Utf8Text.compare(left.key, right.key) }
            .map { (device, value) ->
                MediaDeviceTotal(
                    deviceId = device,
                    seconds = value.first.longValueExact(),
                    events = value.second,
                )
            }
    }

    private fun resumeWinner(rows: List<MediaEventRow>): MediaEventRow? =
        rows.filter { it.kind == MediaKind.RESUME }.maxWithOrNull(::compareCandidates)

    private fun subtitleWinners(rows: List<MediaEventRow>): List<MediaSubtitle> {
        val winners = LinkedHashMap<String, SubtitleCue>()
        for (row in rows) {
            if (row.kind != MediaKind.SUBTITLE) continue
            val cue = SubtitleCue.from(row)
            val slot = "${row.resourceId}\u001f${cue.planId}\u001f${cue.version}\u001f${cue.index}"
            val previous = winners[slot]
            if (previous == null || compareCandidates(cue.row, previous.row) > 0) winners[slot] = cue
        }
        return winners.values
            .sortedWith { left, right -> compareSubtitles(left, right) }
            .map { it.toSubtitle() }
    }

    private fun compareSubtitles(left: SubtitleCue, right: SubtitleCue): Int {
        val plan = Utf8Text.compare(left.planId, right.planId)
        if (plan != 0) return plan
        val version = left.version.compareTo(right.version)
        return if (version != 0) version else left.index.compareTo(right.index)
    }

    private fun latestPlanVersions(subtitles: List<MediaSubtitle>): List<MediaPlanVersion> {
        val latest = LinkedHashMap<String, Long>()
        for (subtitle in subtitles) {
            latest[subtitle.planId] = maxOf(latest[subtitle.planId] ?: 0, subtitle.version)
        }
        return latest.entries
            .sortedWith { left, right -> Utf8Text.compare(left.key, right.key) }
            .map { MediaPlanVersion(it.key, it.value) }
    }

    private fun compareCandidates(left: MediaEventRow, right: MediaEventRow): Int {
        val time = left.observedAt.epochMillis.compareTo(right.observedAt.epochMillis)
        if (time != 0) return time
        val device = Utf8Text.compare(left.deviceId, right.deviceId)
        if (device != 0) return device
        return Utf8Text.compare(left.eventId, right.eventId)
    }

    private data class SubtitleCue(
        val row: MediaEventRow,
        val planId: String,
        val version: Long,
        val index: Long,
        val text: String,
    ) {
        fun toSubtitle(): MediaSubtitle = MediaSubtitle(
            planId = planId,
            version = version,
            index = index,
            text = text,
            startMillis = row.positionMillis ?: 0,
            endMillis = row.durationMillis ?: 0,
            eventId = row.eventId,
        )

        companion object {
            fun from(row: MediaEventRow): SubtitleCue {
                val planId = (row.data["plan_id"] as? JsonValue.Text)?.value
                    ?: mediaError("INVALID_SUBTITLE", "subtitle plan_id")
                val version = (row.data["version"] as? JsonValue.Number)
                    ?: mediaError("INVALID_SUBTITLE", "subtitle version")
                val index = (row.data["index"] as? JsonValue.Number)
                    ?: mediaError("INVALID_SUBTITLE", "subtitle index")
                val text = (row.data["text"] as? JsonValue.Text)?.value
                    ?: mediaError("INVALID_SUBTITLE", "subtitle text")
                return SubtitleCue(
                    row = row,
                    planId = planId,
                    version = subtitleAmount(version.literal, "version", 1, MediaContract.SAFE_MILLISECOND_MAX),
                    index = subtitleAmount(index.literal, "index", 0, MediaContract.SAFE_MILLISECOND_MAX),
                    text = text,
                )
            }
        }
    }

    private fun subtitleAmount(value: String, label: String, minimum: Long, maximum: Long): Long {
        val decimal = BigDecimal(value)
        if (decimal.stripTrailingZeros().scale() > 0) {
            mediaError("INVALID_SUBTITLE", "subtitle $label must be an integer")
        }
        val amount = try {
            decimal.longValueExact()
        } catch (failure: ArithmeticException) {
            mediaError("INVALID_SUBTITLE", "subtitle $label must be an integer")
        }
        if (amount < minimum || amount > maximum) {
            mediaError("INVALID_SUBTITLE", "subtitle $label out of range")
        }
        return amount
    }
}

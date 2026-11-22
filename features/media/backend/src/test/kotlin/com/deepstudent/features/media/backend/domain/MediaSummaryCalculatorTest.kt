package com.deepstudent.features.media.backend.domain

import com.deepstudent.core.common.json.JsonFields
import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.features.media.api.MediaEventNormalizer
import com.deepstudent.features.media.api.MediaEventRow
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaSummaryCalculatorTest {
    private val fixture = RepoFixtures.json("fixtures/media-time.json")
    private val resourceId = JsonFields.string(fixture, "resourceId")
    private val logicalDate = JsonFields.string(fixture, "logicalDate")
    private val expected = fixture["expectedSummary"] as JsonValue.Object
    private val rows: List<MediaEventRow> = (fixture["events"] as JsonValue.Array).values.map {
        MediaEventNormalizer.toRow(MediaEventNormalizer.decode(it as JsonValue.Object))
    }

    @Test
    fun frozenVectorMatchesDesktopSummaryReduction() {
        val summary = MediaSummaryCalculator.summarize(rows, resourceId, logicalDate)
        assertEquals(JsonFields.integer(expected, "seconds"), summary.seconds)
        assertEquals(JsonFields.integer(expected, "position"), summary.position)
        assertEquals(JsonFields.integer(expected, "position_ms"), summary.positionMillis)
        assertEquals(JsonFields.integer(expected, "duration_ms"), summary.durationMillis)
        assertEquals(JsonFields.string(expected, "resume_event_id"), summary.resumeEventId)
        assertEquals(JsonFields.string(expected, "resume_observed_at"), summary.resumeObservedAt)
        assertEquals(6, summary.eventCount)
        assertEquals(
            listOf("device-a" to 90L to 3, "device-b" to 30L to 3),
            summary.byDevice.map { it.deviceId to it.seconds to it.events },
        )
        assertEquals(1, summary.subtitles.size)
        assertEquals("s022-subtitle-b", summary.subtitles[0].eventId)
        assertEquals("First device subtitle", summary.subtitles[0].text)
        assertEquals(listOf("public-media-plan" to 2L), summary.latestPlanVersions.map { it.planId to it.version })
    }

    @Test
    fun replayIsIdempotentAndResumeRewindWins() {
        val first = MediaSummaryCalculator.summarize(rows, resourceId, logicalDate)
        val replay = MediaSummaryCalculator.summarize(rows + rows, resourceId, logicalDate)
        assertEquals(first, replay)
        assertEquals(12000L, replay.positionMillis)
        assertEquals("s022-resume-b", replay.resumeEventId)
    }
}

package com.deepstudent.features.review.backend.domain

import com.deepstudent.features.review.api.FsrsCardSnapshot
import com.deepstudent.features.review.api.FsrsDelay
import com.deepstudent.features.review.api.FsrsFourRatings
import com.deepstudent.services.engine.api.fsrs.FsrsMemory
import com.deepstudent.features.review.api.*
import com.deepstudent.features.review.api.FsrsPhase
import com.deepstudent.features.review.api.FsrsReviewScheduler
import com.deepstudent.features.review.api.FsrsScheduleOutcome
import com.deepstudent.features.review.api.FsrsSchedulerConfig
import com.deepstudent.features.review.api.ReviewPlanSnapshot
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReviewPlanFsrsAdapterTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = 1_794_686_400_000L

    @Test
    fun legacyNeverReviewedIsNew() {
        val state = ReviewPlanFsrsRules.resolveState(plan(), null, now, zone)
        assertEquals(FsrsPhase.NEW, state.phase)
        assertNull(state.stability)
        assertNull(state.difficulty)
    }

    @Test
    fun legacyReviewedMapsIntervalAndEase() {
        val state = ReviewPlanFsrsRules.resolveState(
            plan(intervalDays = 12, repetitions = 3, totalReviews = 3, lastReviewDate = "2026-11-03"),
            null,
            now,
            zone,
        )
        assertEquals(FsrsPhase.REVIEW, state.phase)
        assertEquals(12.0, state.stability!!, 0.0)
        assertEquals(5.0, state.difficulty!!, 1e-9)
        assertEquals("2026-11-03", ReviewPlanFsrsRules.localDateKey(state.lastReviewAt!!, zone))
    }

    @Test
    fun legacyFailedMapsToRelearning() {
        val state = ReviewPlanFsrsRules.resolveState(
            plan(repetitions = 0, totalReviews = 4, consecutiveFailures = 2, easeFactor = 1.7),
            null,
            now,
            zone,
        )
        assertEquals(FsrsPhase.RELEARNING, state.phase)
        assertEquals(1.0, state.stability!!, 0.0)
        assertEquals(2, state.lapses)
    }

    @Test
    fun qualityMappingAndFourPreviewsStayDistinct() {
        assertEquals(
            listOf(1, 1, 1, 2, 3, 4),
            listOf(0, 1, 2, 3, 4, 5).map { ReviewPlanFsrsRules.ratingFromQuality(it).value },
        )
        val adapter = ReviewPlanFsrsAdapter(StubScheduler(), zone)
        val previews = adapter.previews(plan(), null, now)
        assertEquals(1L, previews.again.intervalMinutes)
        assertEquals(2L, previews.hard.intervalMinutes)
        assertEquals(3L, previews.good.intervalMinutes)
        assertEquals(4L * 1440L, previews.easy.intervalMinutes)
    }

    private fun plan(
        easeFactor: Double = 2.5,
        intervalDays: Int = 0,
        repetitions: Int = 0,
        totalReviews: Int = 0,
        consecutiveFailures: Int = 0,
        lastReviewDate: String? = null,
    ) = ReviewPlanSnapshot(
        id = "rp_fsrs",
        questionId = "q1",
        examId = "e1",
        schedule = ReviewPlanLegacySchedule(easeFactor, intervalDays, repetitions, "2026-11-15", lastReviewDate, "new"),
        statistics = ReviewPlanStatistics(totalReviews, 0, consecutiveFailures, false),
        times = FsrsRowTimes("2026-11-01T00:00:00Z", "2026-11-01T00:00:00Z"),
    )

    private class StubScheduler : FsrsReviewScheduler {
        override fun scheduleAll(
            card: FsrsCardSnapshot,
            nowMs: Long,
            config: FsrsSchedulerConfig,
        ): FsrsFourRatings = FsrsFourRatings(
            outcome(nowMs, 1, FsrsPhase.LEARNING, FsrsDelay.Minutes(1.0)),
            outcome(nowMs, 2, FsrsPhase.LEARNING, FsrsDelay.Minutes(2.0)),
            outcome(nowMs, 3, FsrsPhase.LEARNING, FsrsDelay.Minutes(3.0)),
            outcome(nowMs, 4, FsrsPhase.REVIEW, FsrsDelay.Days(4)),
        )

        private fun outcome(
            nowMs: Long,
            step: Int,
            phase: FsrsPhase,
            delay: FsrsDelay,
        ): FsrsScheduleOutcome = FsrsScheduleOutcome(
            phase = phase,
            memory = FsrsMemory(step.toDouble(), 5.0),
            delay = delay,
            scheduledDays = if (delay is FsrsDelay.Days) delay.days else 0,
            elapsedDays = 0,
            dueMs = when (delay) {
                is FsrsDelay.Minutes -> nowMs + (delay.minutes * 60_000).toLong()
                is FsrsDelay.Days -> nowMs + delay.days * 86_400_000L
            },
            counts = FsrsReviewCounts(1, 0),
            learningStep = step - 1,
        )
    }
}

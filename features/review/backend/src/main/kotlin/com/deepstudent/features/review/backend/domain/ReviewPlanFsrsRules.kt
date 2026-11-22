package com.deepstudent.features.review.backend.domain

import com.deepstudent.features.review.api.FsrsPhase
import com.deepstudent.features.review.api.FsrsRating
import com.deepstudent.features.review.api.MS_PER_DAY
import com.deepstudent.features.review.api.ReviewPlanFsrsState
import com.deepstudent.features.review.api.ReviewPlanSnapshot
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.max
import kotlin.math.round

object ReviewPlanFsrsRules {
    private const val MIN_EASE = 1.3
    private const val BASE_EASE = 2.5
    private const val SLOPE = 5.0 / (BASE_EASE - MIN_EASE)

    fun ratingFromQuality(quality: Int): FsrsRating = FsrsRating.fromQuality(quality)

    fun passed(rating: FsrsRating): Boolean = rating.passed

    fun difficultyFromEase(easeFactor: Double): Double {
        val ease = if (easeFactor.isFinite()) easeFactor else BASE_EASE
        return (10.0 - (max(MIN_EASE, ease) - MIN_EASE) * SLOPE).coerceIn(1.0, 10.0)
    }

    fun easeFromDifficulty(difficulty: Double): Double {
        val clamped = difficulty.coerceIn(1.0, 10.0)
        val value = MIN_EASE + (10.0 - clamped) / SLOPE
        return max(MIN_EASE, round(value * 1_000.0) / 1_000.0)
    }

    fun resolveState(
        plan: ReviewPlanSnapshot,
        stored: ReviewPlanFsrsState?,
        nowMs: Long,
        zone: ZoneId,
    ): ReviewPlanFsrsState {
        if (stored?.phase != null) return stored
        val difficulty = difficultyFromEase(plan.easeFactor)
        val lastReview = plan.lastReviewDate?.let { localNoonMs(it, zone) }
            ?: if (plan.totalReviews > 0) nowMs - max(1, plan.intervalDays) * MS_PER_DAY else null
        if (plan.totalReviews == 0 && plan.repetitions == 0) {
            return ReviewPlanFsrsState(null, null, FsrsPhase.NEW, 0, 0, null, null)
        }
        if (plan.repetitions == 0) {
            return ReviewPlanFsrsState(
                stability = 1.0,
                difficulty = difficulty,
                phase = FsrsPhase.RELEARNING,
                learningStep = 0,
                lapses = max(1, plan.consecutiveFailures),
                dueAt = null,
                lastReviewAt = lastReview,
            )
        }
        return ReviewPlanFsrsState(
            stability = max(1, plan.intervalDays).toDouble(),
            difficulty = difficulty,
            phase = FsrsPhase.REVIEW,
            learningStep = 0,
            lapses = 0,
            dueAt = null,
            lastReviewAt = lastReview,
        )
    }

    fun localNoonMs(dateKey: String, zone: ZoneId): Long? = try {
        LocalDate.parse(dateKey).atTime(LocalTime.NOON).atZone(zone).toInstant().toEpochMilli()
    } catch (_: RuntimeException) {
        null
    }

    fun localDateKey(ms: Long, zone: ZoneId): String =
        Instant.ofEpochMilli(ms).atZone(zone).toLocalDate().toString()
}

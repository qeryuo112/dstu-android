package com.deepstudent.features.review.backend.domain

import com.deepstudent.features.review.api.FsrsCardSnapshot
import com.deepstudent.features.review.api.FsrsPhase
import com.deepstudent.features.review.api.FsrsRating
import com.deepstudent.features.review.api.FsrsReviewScheduler
import com.deepstudent.features.review.api.FsrsScheduleOutcome
import com.deepstudent.features.review.api.FsrsSchedulerConfig
import com.deepstudent.features.review.api.ReviewPlanFsrsScheduler
import com.deepstudent.features.review.api.ReviewPlanFsrsState
import com.deepstudent.features.review.api.ReviewPlanOutcome
import com.deepstudent.features.review.api.ReviewPlanRatingPreview
import com.deepstudent.features.review.api.ReviewPlanRatingPreviews
import com.deepstudent.features.review.api.ReviewPlanSnapshot
import com.deepstudent.services.engine.api.fsrs.FsrsMemory
import java.time.ZoneId
import kotlin.math.max
import kotlin.math.roundToLong

class ReviewPlanFsrsAdapter(
    private val scheduler: FsrsReviewScheduler,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val config: FsrsSchedulerConfig = FsrsSchedulerConfig(),
) : ReviewPlanFsrsScheduler {
    override fun resolveState(
        plan: ReviewPlanSnapshot,
        stored: ReviewPlanFsrsState?,
        nowMs: Long,
    ): ReviewPlanFsrsState = ReviewPlanFsrsRules.resolveState(plan, stored, nowMs, zone)

    override fun scheduleAll(
        plan: ReviewPlanSnapshot,
        stored: ReviewPlanFsrsState?,
        nowMs: Long,
    ): List<ReviewPlanOutcome> {
        val state = resolveState(plan, stored, nowMs)
        val card = cardSnapshot(plan, state, nowMs)
        val outcomes = scheduler.scheduleAll(card, nowMs, config)
        return FsrsRating.entries.map { rating ->
            toPlanOutcome(rating, outcomes[rating], nowMs, state.lapses)
        }
    }

    override fun schedule(
        plan: ReviewPlanSnapshot,
        stored: ReviewPlanFsrsState?,
        quality: Int,
        nowMs: Long,
    ): ReviewPlanOutcome {
        val rating = ReviewPlanFsrsRules.ratingFromQuality(quality)
        return scheduleAll(plan, stored, nowMs)[rating.value - 1]
    }

    override fun previews(
        plan: ReviewPlanSnapshot,
        stored: ReviewPlanFsrsState?,
        nowMs: Long,
    ): ReviewPlanRatingPreviews {
        val outcomes = scheduleAll(plan, stored, nowMs)
        val preview = outcomes.map { outcome ->
            ReviewPlanRatingPreview(outcome.intervalMinutes, outcome.nextReviewDate)
        }
        return ReviewPlanRatingPreviews(preview[0], preview[1], preview[2], preview[3])
    }

    private fun cardSnapshot(
        plan: ReviewPlanSnapshot,
        state: ReviewPlanFsrsState,
        nowMs: Long,
    ): FsrsCardSnapshot {
        val stability = state.stability
        val difficulty = state.difficulty
        val memory = if (stability != null && difficulty != null) {
            FsrsMemory(stability, difficulty)
        } else {
            null
        }
        val lastReviewAt = state.lastReviewAt
        val lastReview = when {
            lastReviewAt == null -> null
            lastReviewAt <= nowMs -> lastReviewAt
            else -> nowMs
        }
        return FsrsCardSnapshot(
            id = plan.id,
            phase = state.phase ?: FsrsPhase.NEW,
            memory = memory,
            learningStep = state.learningStep,
            reps = plan.totalReviews,
            lapses = state.lapses,
            lastReviewMs = lastReview,
        )
    }

    private fun toPlanOutcome(
        rating: FsrsRating,
        outcome: FsrsScheduleOutcome,
        nowMs: Long,
        lapsesBefore: Int,
    ): ReviewPlanOutcome {
        val intervalMinutes = max(0L, ((outcome.dueMs - nowMs) / 60_000.0).roundToLong())
        val intervalDays = max(0, outcome.scheduledDays)
        val state = ReviewPlanFsrsState(
            stability = outcome.memory.stability,
            difficulty = outcome.memory.difficulty,
            phase = outcome.phase,
            learningStep = outcome.learningStep,
            lapses = max(lapsesBefore, outcome.lapses),
            dueAt = outcome.dueMs,
            lastReviewAt = nowMs,
        )
        return ReviewPlanOutcome(
            rating = rating,
            passed = rating.passed,
            intervalDays = intervalDays,
            nextReviewDate = ReviewPlanFsrsRules.localDateKey(outcome.dueMs, zone),
            intervalMinutes = intervalMinutes,
            fsrs = state,
            easeFactor = ReviewPlanFsrsRules.easeFromDifficulty(outcome.memory.difficulty),
            phase = outcome.phase,
        )
    }
}

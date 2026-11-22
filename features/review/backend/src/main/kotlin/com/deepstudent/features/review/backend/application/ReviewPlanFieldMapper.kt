package com.deepstudent.features.review.backend.application

import com.deepstudent.features.review.api.ReviewPlanFsrsColumns
import com.deepstudent.features.review.api.ReviewPlanFsrsState
import com.deepstudent.features.review.api.ReviewPlanOutcome
import com.deepstudent.features.review.api.ReviewPlanSnapshot
import com.deepstudent.features.review.backend.domain.ReviewPlanFsrsRules
import java.time.ZoneId
import kotlin.math.max

object ReviewPlanFieldMapper {
    fun toColumns(
        outcome: ReviewPlanOutcome,
    ): ReviewPlanFsrsColumns = ReviewPlanFsrsColumns(
        easeFactor = outcome.easeFactor,
        intervalDays = outcome.intervalDays,
        nextReviewDate = outcome.nextReviewDate,
        state = outcome.fsrs,
        phase = outcome.phase,
        passed = outcome.passed,
    )

    fun nextReviewDate(outcome: ReviewPlanOutcome): String = outcome.nextReviewDate

    fun stateWithPrevious(
        outcome: ReviewPlanOutcome,
        previous: ReviewPlanFsrsState?,
        nowMs: Long,
    ): ReviewPlanFsrsState = outcome.fsrs.copy(
        lapses = max(previous?.lapses ?: 0, outcome.fsrs.lapses),
        lastReviewAt = nowMs,
    )

    fun dueDateFromState(
        plan: ReviewPlanSnapshot,
        state: ReviewPlanFsrsState,
        nowMs: Long,
        zone: ZoneId,
    ): String {
        val due = state.dueAt ?: plan.nextReviewDate.let { ReviewPlanFsrsRules.localNoonMs(it, zone) } ?: nowMs
        return ReviewPlanFsrsRules.localDateKey(due, zone)
    }
}

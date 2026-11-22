package com.deepstudent.features.review.backend.application

import com.deepstudent.features.review.api.FsrsCardSnapshot
import com.deepstudent.features.review.api.FsrsCardStateRow
import com.deepstudent.features.review.api.FsrsRating
import com.deepstudent.features.review.api.FsrsReviewLogRow
import com.deepstudent.features.review.api.FsrsScheduleOutcome
import com.deepstudent.services.engine.api.fsrs.FsrsMemory
import com.deepstudent.features.review.api.FsrsCardScheduleColumns
import com.deepstudent.features.review.api.FsrsReviewCounts
import com.deepstudent.features.review.api.FsrsDueColumns
import com.deepstudent.features.review.api.FsrsLogState
import com.deepstudent.features.review.api.FsrsLogTiming

/**
 * Pure column mapping. Scheduling and training never read or write SQL directly;
 * feature-data adapters use these functions to keep wire fields and DB columns aligned.
 */
object FsrsFieldMapper {
    fun snapshot(row: FsrsCardStateRow): FsrsCardSnapshot {
        val stability = row.stability
        val difficulty = row.difficulty
        val memory = if (stability != null && difficulty != null) {
            FsrsMemory(stability, difficulty)
        } else {
            null
        }
        return FsrsCardSnapshot(
            id = row.id,
            phase = row.phase,
            memory = memory,
            learningStep = row.learningStep,
            reps = row.reps,
            lapses = row.lapses,
            lastReviewMs = row.lastReviewMs,
        )
    }

    fun applyOutcome(
        row: FsrsCardStateRow,
        outcome: FsrsScheduleOutcome,
        reviewMs: Long,
        updatedAt: String,
        paramsVersion: String,
        desiredRetention: Double,
    ): FsrsCardStateRow = row.copy(
        schedule = FsrsCardScheduleColumns(outcome.phase, outcome.memory.stability, outcome.memory.difficulty,
            outcome.elapsedDays.toDouble(), outcome.scheduledDays.toDouble(), FsrsReviewCounts(outcome.reps, outcome.lapses),
            FsrsDueColumns(outcome.dueMs, reviewMs), outcome.learningStep),
        policy = row.policy.copy(fsrsParamsVersion = paramsVersion, desiredRetention = desiredRetention),
        times = row.times.copy(updatedAt = updatedAt),
    )

    fun reviewLog(
        logId: String,
        before: FsrsCardStateRow,
        outcome: FsrsScheduleOutcome,
        rating: FsrsRating,
        reviewMs: Long,
        durationMs: Long?,
        paramsVersion: String,
    ): FsrsReviewLogRow = FsrsReviewLogRow(
        id = logId,
        cardStateId = before.id,
        ankiCardId = before.ankiCardId,
        rating = rating,
        before = FsrsLogState(before.phase, before.stability, before.difficulty, before.dueMs),
        after = FsrsLogState(outcome.phase, outcome.memory.stability, outcome.memory.difficulty, outcome.dueMs),
        timing = FsrsLogTiming(outcome.scheduledDays.toDouble(), outcome.elapsedDays.toDouble(), reviewMs, durationMs),
        fsrsParamsVersion = paramsVersion,
    )
}

package com.deepstudent.features.review.api

import com.deepstudent.services.engine.api.fsrs.FsrsTrainingItem

/** `fsrs_card_states` row. Field mapping is deliberately separate from scheduling and training. */
data class FsrsCardStateRow(
    val id: String,
    val ankiCardId: String,
    val deckId: String?,
    val schedule: FsrsCardScheduleColumns,
    val policy: FsrsCardPolicyColumns,
    val times: FsrsRowTimes,
) {
    val phase: FsrsPhase get() = schedule.phase
    val stability: Double? get() = schedule.stability
    val difficulty: Double? get() = schedule.difficulty
    val elapsedDays: Double get() = schedule.elapsedDays
    val scheduledDays: Double get() = schedule.scheduledDays
    val reps: Int get() = schedule.counts.reps
    val lapses: Int get() = schedule.counts.lapses
    val dueMs: Long get() = schedule.due.dueMs
    val lastReviewMs: Long? get() = schedule.due.lastReviewMs
    val learningStep: Int get() = schedule.learningStep
    val suspended: Boolean get() = policy.suspended
    val fsrsParamsVersion: String get() = policy.fsrsParamsVersion
    val desiredRetention: Double? get() = policy.desiredRetention
    val leech: Boolean get() = policy.leech
    val buriedUntilMs: Long? get() = policy.buriedUntilMs
    val createdAt: String get() = times.createdAt
    val updatedAt: String get() = times.updatedAt
}

data class FsrsCardScheduleColumns(
    val phase: FsrsPhase,
    val stability: Double?,
    val difficulty: Double?,
    val elapsedDays: Double,
    val scheduledDays: Double,
    val counts: FsrsReviewCounts,
    val due: FsrsDueColumns,
    val learningStep: Int,
)

data class FsrsDueColumns(val dueMs: Long, val lastReviewMs: Long?)
data class FsrsRowTimes(val createdAt: String, val updatedAt: String)

data class FsrsCardPolicyColumns(
    val suspended: Boolean,
    val fsrsParamsVersion: String,
    val desiredRetention: Double?,
    val leech: Boolean,
    val buriedUntilMs: Long?,
)

/** `fsrs_review_logs` row. */
data class FsrsReviewLogRow(
    val id: String,
    val cardStateId: String,
    val ankiCardId: String,
    val rating: FsrsRating,
    val before: FsrsLogState,
    val after: FsrsLogState,
    val timing: FsrsLogTiming,
    val fsrsParamsVersion: String,
) {
    val stateBefore: FsrsPhase get() = before.phase
    val stateAfter: FsrsPhase get() = after.phase
    val stabilityBefore: Double? get() = before.stability
    val stabilityAfter: Double? get() = after.stability
    val difficultyBefore: Double? get() = before.difficulty
    val difficultyAfter: Double? get() = after.difficulty
    val dueBeforeMs: Long get() = before.dueMs
    val dueAfterMs: Long get() = after.dueMs
    val scheduledDays: Double get() = timing.scheduledDays
    val elapsedDays: Double get() = timing.elapsedDays
    val reviewMs: Long get() = timing.reviewMs
    val durationMs: Long? get() = timing.durationMs
}

data class FsrsLogState(val phase: FsrsPhase, val stability: Double?, val difficulty: Double?, val dueMs: Long)
data class FsrsLogTiming(
    val scheduledDays: Double,
    val elapsedDays: Double,
    val reviewMs: Long,
    val durationMs: Long?,
)

/** Columns written back to `review_plans`; legacy SM-2 columns are kept for reads. */
data class ReviewPlanFsrsColumns(
    val easeFactor: Double,
    val intervalDays: Int,
    val nextReviewDate: String,
    val state: ReviewPlanFsrsState,
    val phase: FsrsPhase,
    val passed: Boolean,
)

/** One raw `fsrs_review_logs` history row used to build training items. */
data class FsrsHistoryRow(
    val cardStateId: String,
    val ankiCardId: String,
    val rating: Int,
    val reviewMs: Long,
    val stateBefore: Int,
    val memoryBefore: FsrsMemoryColumns,
    val elapsedDays: Double?,
    val snapshotLastReviewMs: Long?,
) {
    val stabilityBefore: Double? get() = memoryBefore.stability
    val difficultyBefore: Double? get() = memoryBefore.difficulty
}

data class FsrsMemoryColumns(val stability: Double?, val difficulty: Double?)

data class FsrsTrainingSet(
    val items: List<FsrsTrainingItem>,
    val cardIds: List<Long>,
    val cardCount: Int,
    val reviewCount: Int,
)

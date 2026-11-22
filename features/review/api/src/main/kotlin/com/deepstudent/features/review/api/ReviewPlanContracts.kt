package com.deepstudent.features.review.api

data class ReviewPlanSnapshot(
    val id: String,
    val questionId: String,
    val examId: String,
    val schedule: ReviewPlanLegacySchedule,
    val statistics: ReviewPlanStatistics,
    val times: FsrsRowTimes,
) {
    val easeFactor: Double get() = schedule.easeFactor
    val intervalDays: Int get() = schedule.intervalDays
    val repetitions: Int get() = schedule.repetitions
    val nextReviewDate: String get() = schedule.nextReviewDate
    val lastReviewDate: String? get() = schedule.lastReviewDate
    val status: String get() = schedule.status
    val totalReviews: Int get() = statistics.totalReviews
    val totalCorrect: Int get() = statistics.totalCorrect
    val consecutiveFailures: Int get() = statistics.consecutiveFailures
    val isDifficult: Boolean get() = statistics.isDifficult
    val createdAt: String get() = times.createdAt
    val updatedAt: String get() = times.updatedAt
    init {
        require(id.isNotEmpty() && questionId.isNotEmpty()) { "review plan identity must not be empty" }
        require(intervalDays >= 0 && repetitions >= 0 && totalReviews >= 0) { "review plan counters must be non-negative" }
    }
}

data class ReviewPlanLegacySchedule(
    val easeFactor: Double,
    val intervalDays: Int,
    val repetitions: Int,
    val nextReviewDate: String,
    val lastReviewDate: String?,
    val status: String,
)

data class ReviewPlanStatistics(
    val totalReviews: Int,
    val totalCorrect: Int,
    val consecutiveFailures: Int,
    val isDifficult: Boolean,
)

data class ReviewPlanFsrsState(
    val stability: Double?,
    val difficulty: Double?,
    val phase: FsrsPhase?,
    val learningStep: Int,
    val lapses: Int,
    val dueAt: Long?,
    val lastReviewAt: Long?,
) {
    init {
        require(learningStep >= 0 && lapses >= 0) {
            "review plan FSRS counters must be non-negative"
        }
    }

    val legacy: Boolean get() = phase == null
}

data class ReviewPlanOutcome(
    val rating: FsrsRating,
    val passed: Boolean,
    val intervalDays: Int,
    val nextReviewDate: String,
    val intervalMinutes: Long,
    val fsrs: ReviewPlanFsrsState,
    val easeFactor: Double,
    val phase: FsrsPhase,
)

data class ReviewPlanRatingPreview(
    val intervalMinutes: Long,
    val nextReviewDate: String,
)

data class ReviewPlanRatingPreviews(
    val again: ReviewPlanRatingPreview,
    val hard: ReviewPlanRatingPreview,
    val good: ReviewPlanRatingPreview,
    val easy: ReviewPlanRatingPreview,
)

interface ReviewPlanFsrsScheduler {
    fun resolveState(
        plan: ReviewPlanSnapshot,
        stored: ReviewPlanFsrsState?,
        nowMs: Long,
    ): ReviewPlanFsrsState

    fun scheduleAll(
        plan: ReviewPlanSnapshot,
        stored: ReviewPlanFsrsState?,
        nowMs: Long,
    ): List<ReviewPlanOutcome>

    fun schedule(
        plan: ReviewPlanSnapshot,
        stored: ReviewPlanFsrsState?,
        quality: Int,
        nowMs: Long,
    ): ReviewPlanOutcome

    fun previews(
        plan: ReviewPlanSnapshot,
        stored: ReviewPlanFsrsState?,
        nowMs: Long,
    ): ReviewPlanRatingPreviews
}

package com.deepstudent.features.review.api

import com.deepstudent.services.engine.api.fsrs.FsrsMemory

const val FSRS_PARAMS_VERSION: String = "fsrs-rs-6.6"
const val DEFAULT_DECK_ID: String = "deck_default"
const val DEFAULT_DESIRED_RETENTION: Double = 0.9
const val DEFAULT_NEW_PER_DAY: Int = 20
const val DEFAULT_REVIEWS_PER_DAY: Int = 200
const val DEFAULT_LEECH_THRESHOLD: Int = 8
const val MS_PER_DAY: Long = 86_400_000L
const val MS_PER_MINUTE: Long = 60_000L

enum class FsrsRating(val value: Int) {
    AGAIN(1),
    HARD(2),
    GOOD(3),
    EASY(4),
    ;

    val passed: Boolean get() = this != AGAIN

    companion object {
        fun fromValue(value: Int): FsrsRating =
            entries.firstOrNull { it.value == value }
                ?: throw IllegalArgumentException("FSRS rating must be 1..4: $value")

        /** 题库 quality 0..2 -> Again，3 -> Hard，4 -> Good，>=5 -> Easy。 */
        fun fromQuality(quality: Int): FsrsRating = when {
            quality <= 2 -> AGAIN
            quality == 3 -> HARD
            quality == 4 -> GOOD
            else -> EASY
        }
    }
}

enum class FsrsPhase(val state: Int, val wire: String) {
    NEW(0, "new"),
    LEARNING(1, "learning"),
    REVIEW(2, "review"),
    RELEARNING(3, "relearning"),
    ;

    companion object {
        fun fromState(state: Int): FsrsPhase =
            entries.firstOrNull { it.state == state }
                ?: throw IllegalArgumentException("FSRS state must be 0..3: $state")

        fun fromWire(wire: String?): FsrsPhase? =
            entries.firstOrNull { it.wire == wire }
    }
}

data class FsrsCardSnapshot(
    val id: String,
    val phase: FsrsPhase,
    val memory: FsrsMemory?,
    val learningStep: Int,
    val reps: Int,
    val lapses: Int,
    val lastReviewMs: Long?,
) {
    init {
        require(id.isNotEmpty()) { "FSRS card id must not be empty" }
        require(learningStep >= 0) { "FSRS learning step must be non-negative" }
        require(reps >= 0 && lapses >= 0) { "FSRS counts must be non-negative" }
    }
}

data class FsrsSchedulerConfig(
    val desiredRetention: Double = DEFAULT_DESIRED_RETENTION,
    val enableFuzz: Boolean = true,
    val learningSteps: List<Double> = listOf(1.0, 10.0),
    val relearningSteps: List<Double> = listOf(10.0),
    val maximumInterval: Int = 36_500,
    val dayRolloverHour: Int = 4,
    val fsrsParams: List<Double> = emptyList(),
    val queue: FsrsQueuePreferences = FsrsQueuePreferences(),
) {
    init {
        require(desiredRetention > 0 && desiredRetention < 1) {
            "FSRS desired retention must be within (0, 1)"
        }
        require(maximumInterval >= 1) { "FSRS maximum interval must be positive" }
        require(dayRolloverHour in 0..23) { "FSRS rollover hour must be within 0..23" }
    }
}

data class FsrsQueuePreferences(
    val newPerDay: Int = DEFAULT_NEW_PER_DAY,
    val reviewsPerDay: Int = DEFAULT_REVIEWS_PER_DAY,
    val learnAheadMinutes: Int = 15,
    val leeches: FsrsLeechPreferences = FsrsLeechPreferences(),
    val buryNewSiblings: Boolean = true,
    val buryReviewSiblings: Boolean = true,
    val order: FsrsQueueOrder = FsrsQueueOrder(),
    val maxAnswerSeconds: Int = 60,
)

data class FsrsLeechPreferences(val threshold: Int = DEFAULT_LEECH_THRESHOLD, val action: String = "suspend")
data class FsrsQueueOrder(val reviewOrder: String = "due", val newReviewOrder: String = "after")

sealed interface FsrsDelay {
    data class Minutes(val minutes: Double) : FsrsDelay

    data class Days(val days: Int) : FsrsDelay
}

data class FsrsScheduleOutcome(
    val phase: FsrsPhase,
    val memory: FsrsMemory,
    val delay: FsrsDelay,
    val scheduledDays: Int,
    val elapsedDays: Long,
    val dueMs: Long,
    val counts: FsrsReviewCounts,
    val learningStep: Int,
) {
    val reps: Int get() = counts.reps
    val lapses: Int get() = counts.lapses
}

data class FsrsReviewCounts(val reps: Int, val lapses: Int)

data class FsrsFourRatings(
    val again: FsrsScheduleOutcome,
    val hard: FsrsScheduleOutcome,
    val good: FsrsScheduleOutcome,
    val easy: FsrsScheduleOutcome,
) {
    operator fun get(rating: FsrsRating): FsrsScheduleOutcome = when (rating) {
        FsrsRating.AGAIN -> again
        FsrsRating.HARD -> hard
        FsrsRating.GOOD -> good
        FsrsRating.EASY -> easy
    }
}

fun interface FsrsReviewScheduler {
    fun scheduleAll(
        card: FsrsCardSnapshot,
        nowMs: Long,
        config: FsrsSchedulerConfig,
    ): FsrsFourRatings
}

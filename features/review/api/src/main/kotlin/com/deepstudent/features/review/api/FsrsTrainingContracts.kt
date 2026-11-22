package com.deepstudent.features.review.api

import com.deepstudent.services.engine.api.fsrs.FsrsCancellation
import com.deepstudent.services.engine.api.fsrs.FsrsModelEvaluation
import com.deepstudent.services.engine.api.fsrs.FsrsTrainingItem

enum class FsrsTrainingStatus {
    OPTIMIZED,
    ALREADY_OPTIMAL,
    NOT_ENOUGH_DATA,
    CANCELLED,
    CONFLICT,
}

data class FsrsTrainingRequest(
    val currentParams: List<Double>,
    val items: List<FsrsTrainingItem>,
    val cardIds: List<Long>? = null,
    val cardCount: Int = 0,
    val reviewCount: Int = 0,
    val relearningStepsCount: Int = 1,
    val enableShortTerm: Boolean = true,
    val cancellation: FsrsCancellation = FsrsCancellation.NONE,
) {
    init {
        require(cardIds == null || cardIds.size == items.size) {
            "FSRS card ids must align with the training items"
        }
        require(cardCount >= 0 && reviewCount >= 0) {
            "FSRS training counts must be non-negative"
        }
    }
}

data class FsrsTrainingResult(
    val status: FsrsTrainingStatus,
    val cardCount: Int,
    val itemCount: Int,
    val reviewCount: Int,
    val current: FsrsModelEvaluation?,
    val optimized: FsrsModelEvaluation?,
    val params: List<Double>,
)

fun interface FsrsTrainer {
    fun train(request: FsrsTrainingRequest): FsrsTrainingResult
}

data class FsrsTrainingCommit(
    val params: List<Double>,
    val reviewCount: Int,
    val optimizedAtMs: Long,
)

/** Atomic compare-and-set commit; false means parameters changed during training. */
fun interface FsrsParameterStore {
    fun commit(expected: List<Double>, update: FsrsTrainingCommit): Boolean
}

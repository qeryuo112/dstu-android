package com.deepstudent.services.engine.api.fsrs

const val FSRS_NATIVE_ENGINE_VERSION: String = "fsrs-6.6.2"

fun interface FsrsCancellation {
    fun isCancelled(): Boolean

    companion object {
        val NONE: FsrsCancellation = FsrsCancellation { false }
    }
}

class FsrsEngineException(
    val kind: String,
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

data class FsrsMemory(
    val stability: Double,
    val difficulty: Double,
) {
    init {
        require(stability.isFinite() && difficulty.isFinite()) {
            "FSRS memory must be finite"
        }
    }
}

data class FsrsReviewInput(
    val rating: Int,
    val deltaTDays: Long,
) {
    init {
        require(rating in 1..4) { "FSRS review rating must be 1..4" }
        require(deltaTDays >= 0) { "FSRS delta_t must be non-negative" }
    }
}

data class FsrsTrainingItem(
    val reviews: List<FsrsReviewInput>,
) {
    init {
        require(reviews.isNotEmpty()) { "FSRS training item must contain reviews" }
    }
}

data class FsrsNextState(
    val memory: FsrsMemory,
    val intervalDays: Double,
)

data class FsrsNextStates(
    val again: FsrsNextState,
    val hard: FsrsNextState,
    val good: FsrsNextState,
    val easy: FsrsNextState,
)

data class FsrsModelEvaluation(
    val logLoss: Double,
    val rmseBins: Double,
)

data class FsrsComputeParametersInput(
    val trainSet: List<FsrsTrainingItem>,
    val cardIds: List<Long>? = null,
    val enableShortTerm: Boolean = true,
    val numRelearningSteps: Int? = null,
) {
    init {
        require(cardIds == null || cardIds.size == trainSet.size) {
            "FSRS card ids must align with the training set"
        }
        require(numRelearningSteps == null || numRelearningSteps >= 0) {
            "FSRS relearning step count must be non-negative"
        }
    }
}

/** Numeric engine contract. Scheduling policy and feature field mapping stay above this port. */
interface FsrsNumericEngine {
    fun nextStates(
        parameters: List<Double>,
        memory: FsrsMemory?,
        desiredRetention: Double,
        daysElapsed: Long,
    ): FsrsNextStates

    fun memoryState(
        parameters: List<Double>,
        reviews: List<FsrsReviewInput>,
        startingState: FsrsMemory?,
    ): FsrsMemory

    fun normalizeParameters(parameters: List<Double>): List<Double>

    fun evaluate(
        parameters: List<Double>,
        items: List<FsrsTrainingItem>,
        cancellation: FsrsCancellation = FsrsCancellation.NONE,
    ): FsrsModelEvaluation

    fun optimize(
        input: FsrsComputeParametersInput,
        cancellation: FsrsCancellation = FsrsCancellation.NONE,
    ): List<Double>
}

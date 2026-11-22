package com.deepstudent.features.review.backend.application

import com.deepstudent.features.review.api.FsrsParameterStore
import com.deepstudent.features.review.api.FsrsTrainer
import com.deepstudent.features.review.api.FsrsTrainingCommit
import com.deepstudent.features.review.api.FsrsTrainingRequest
import com.deepstudent.features.review.api.FsrsTrainingResult
import com.deepstudent.features.review.api.FsrsTrainingStatus

class FsrsTrainingService(
    private val trainer: FsrsTrainer,
    private val store: FsrsParameterStore,
) {
    fun train(
        request: FsrsTrainingRequest,
        before: List<Double>,
        optimizedAtMs: Long,
    ): FsrsTrainingResult {
        val result = trainer.train(request.copy(currentParams = before))
        if (result.status != FsrsTrainingStatus.OPTIMIZED) return result
        if (request.cancellation.isCancelled()) return cancelled(request, before)
        val committed = store.commit(
            before,
            FsrsTrainingCommit(result.params, result.reviewCount, optimizedAtMs),
        )
        if (!committed) return result.copy(status = FsrsTrainingStatus.CONFLICT, params = before)
        return result
    }

    private fun cancelled(
        request: FsrsTrainingRequest,
        before: List<Double>,
    ): FsrsTrainingResult = FsrsTrainingResult(
        status = FsrsTrainingStatus.CANCELLED,
        cardCount = request.cardCount,
        itemCount = request.items.size,
        reviewCount = request.reviewCount,
        current = null,
        optimized = null,
        params = before,
    )
}

package com.deepstudent.features.review.backend.application

import com.deepstudent.features.review.api.FsrsTrainer
import com.deepstudent.features.review.api.FsrsTrainingRequest
import com.deepstudent.features.review.api.FsrsTrainingResult
import com.deepstudent.features.review.api.FsrsTrainingStatus
import com.deepstudent.services.engine.api.fsrs.FsrsComputeParametersInput
import com.deepstudent.services.engine.api.fsrs.FsrsEngineException
import com.deepstudent.services.engine.api.fsrs.FsrsModelEvaluation
import com.deepstudent.services.engine.api.fsrs.FsrsNumericEngine

class NativeFsrsTrainer(
    private val engine: FsrsNumericEngine,
) : FsrsTrainer {
    override fun train(request: FsrsTrainingRequest): FsrsTrainingResult {
        val currentParams = engine.normalizeParameters(request.currentParams)
        val base = baseResult(request, currentParams)
        if (request.items.size < MIN_TRAINING_ITEMS) return base
        if (request.cancellation.isCancelled()) return cancelled(request, currentParams)
        val current = evaluateOrCancel(currentParams, request) ?: return cancelled(request, currentParams)
        if (request.cancellation.isCancelled()) return cancelled(request, currentParams)
        val optimizedParams = optimizeOrCancel(request) ?: return cancelled(request, currentParams)
        if (request.cancellation.isCancelled()) return cancelled(request, currentParams)
        val optimized = evaluateOrCancel(optimizedParams, request)
            ?: return cancelled(request, currentParams)
        if (request.cancellation.isCancelled()) return cancelled(request, currentParams)
        return improvedResult(base, current, optimized, optimizedParams)
    }

    private fun baseResult(request: FsrsTrainingRequest, params: List<Double>) =
        FsrsTrainingResult(
            status = FsrsTrainingStatus.NOT_ENOUGH_DATA,
            cardCount = request.cardCount,
            itemCount = request.items.size,
            reviewCount = request.reviewCount,
            current = null,
            optimized = null,
            params = params,
        )

    private fun evaluateOrCancel(
        params: List<Double>,
        request: FsrsTrainingRequest,
    ): EvaluationResult? = try {
        EvaluationResult(evaluateOrNull(engine, params, request))
    } catch (error: FsrsEngineException) {
        if (error.kind == "Interrupted") null else throw error
    }

    private fun optimizeOrCancel(request: FsrsTrainingRequest): List<Double>? = try {
        engine.optimize(
            FsrsComputeParametersInput(
                trainSet = request.items,
                cardIds = request.cardIds,
                enableShortTerm = request.enableShortTerm,
                numRelearningSteps = request.relearningStepsCount,
            ),
            request.cancellation,
        ).map { f32(it) }
    } catch (error: FsrsEngineException) {
        if (error.kind == "Interrupted") null else throw error
    }

    private fun improvedResult(
        base: FsrsTrainingResult,
        current: EvaluationResult?,
        optimized: EvaluationResult,
        optimizedParams: List<Double>,
    ): FsrsTrainingResult {
        val before = current?.value
        val after = optimized.value
        if (after == null) {
            return base.copy(status = FsrsTrainingStatus.ALREADY_OPTIMAL, current = before)
        }
        val improved = before == null ||
            f32(f32(after.logLoss) + f32(1e-6)) < f32(before.logLoss)
        return if (improved) {
            base.copy(
                status = FsrsTrainingStatus.OPTIMIZED,
                current = before,
                optimized = after,
                params = optimizedParams,
            )
        } else {
            base.copy(status = FsrsTrainingStatus.ALREADY_OPTIMAL, current = before, optimized = after)
        }
    }

    private fun evaluateOrNull(
        engine: FsrsNumericEngine,
        params: List<Double>,
        request: FsrsTrainingRequest,
    ): FsrsModelEvaluation? = try {
        engine.evaluate(params, request.items, request.cancellation)
    } catch (error: FsrsEngineException) {
        when (error.kind) {
            "NotEnoughData", "InvalidParameters" -> null
            else -> throw error
        }
    }

    private fun cancelled(
        request: FsrsTrainingRequest,
        params: List<Double>,
    ): FsrsTrainingResult = FsrsTrainingResult(
        status = FsrsTrainingStatus.CANCELLED,
        cardCount = request.cardCount,
        itemCount = request.items.size,
        reviewCount = request.reviewCount,
        current = null,
        optimized = null,
        params = params,
    )

    private fun f32(value: Double): Double = value.toFloat().toDouble()

    private fun f32(value: Float): Double = value.toDouble()

    private data class EvaluationResult(val value: FsrsModelEvaluation?)

    companion object {
        /** fsrs 6.6.2 `MIN_TRAINING_ITEMS`. */
        const val MIN_TRAINING_ITEMS: Int = 8
    }
}

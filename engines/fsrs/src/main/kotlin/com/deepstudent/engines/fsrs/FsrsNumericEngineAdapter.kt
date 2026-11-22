package com.deepstudent.engines.fsrs

import com.deepstudent.services.engine.api.fsrs.FsrsCancellation
import com.deepstudent.services.engine.api.fsrs.FsrsComputeParametersInput
import com.deepstudent.services.engine.api.fsrs.FsrsEngineException
import com.deepstudent.services.engine.api.fsrs.FsrsMemory
import com.deepstudent.services.engine.api.fsrs.FsrsModelEvaluation
import com.deepstudent.services.engine.api.fsrs.FsrsNextState
import com.deepstudent.services.engine.api.fsrs.FsrsNextStates
import com.deepstudent.services.engine.api.fsrs.FsrsNumericEngine
import com.deepstudent.services.engine.api.fsrs.FsrsReviewInput
import com.deepstudent.services.engine.api.fsrs.FsrsTrainingItem
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Android adapter over the frozen `fsrs 6.6.2` JNI bridge. The bridge performs the
 * crate calls; this class only converts values and turns cancellation into the
 * crate's `Interrupted` path.
 */
class FsrsNumericEngineAdapter : FsrsNumericEngine {
    override fun nextStates(
        parameters: List<Double>,
        memory: FsrsMemory?,
        desiredRetention: Double,
        daysElapsed: Long,
    ): FsrsNextStates {
        ensureLoaded()
        val values = callNative {
            FsrsNative.nextStates(
                parameters = parameters.toNativeFloats(),
                hasMemory = memory != null,
                stability = memory?.stability?.toFloat() ?: 0f,
                difficulty = memory?.difficulty?.toFloat() ?: 0f,
                desiredRetention = desiredRetention.toFloat(),
                daysElapsed = daysElapsed,
            )
        }
        require(values.size == 12) { "FSRS nextStates returned ${values.size} values" }
        return FsrsNextStates(
            nextState(values, 0),
            nextState(values, 3),
            nextState(values, 6),
            nextState(values, 9),
        )
    }

    override fun memoryState(
        parameters: List<Double>,
        reviews: List<FsrsReviewInput>,
        startingState: FsrsMemory?,
    ): FsrsMemory {
        ensureLoaded()
        val values = callNative {
            FsrsNative.memoryState(
                parameters = parameters.toNativeFloats(),
                ratings = reviews.map { it.rating }.toIntArray(),
                deltas = reviews.map { it.deltaTDays }.toLongArray(),
                hasStarting = startingState != null,
                stability = startingState?.stability?.toFloat() ?: 0f,
                difficulty = startingState?.difficulty?.toFloat() ?: 0f,
            )
        }
        require(values.size == 2) { "FSRS memoryState returned ${values.size} values" }
        return FsrsMemory(values[0].toDouble(), values[1].toDouble())
    }

    override fun normalizeParameters(parameters: List<Double>): List<Double> {
        ensureLoaded()
        return callNative { FsrsNative.normalizeParameters(parameters.toNativeFloats()) }
            .map { it.toDouble() }
    }

    override fun evaluate(
        parameters: List<Double>,
        items: List<FsrsTrainingItem>,
        cancellation: FsrsCancellation,
    ): FsrsModelEvaluation {
        ensureLoaded()
        val flat = flatten(items)
        val values = withCancellation(cancellation) { token ->
            callNative {
                FsrsNative.evaluate(
                    parameters = parameters.toNativeFloats(),
                    offsets = flat.offsets,
                    ratings = flat.ratings,
                    deltas = flat.deltas,
                    cancelToken = token,
                )
            }
        }
        require(values.size == 2) { "FSRS evaluate returned ${values.size} values" }
        return FsrsModelEvaluation(values[0].toDouble(), values[1].toDouble())
    }

    override fun optimize(
        input: FsrsComputeParametersInput,
        cancellation: FsrsCancellation,
    ): List<Double> {
        ensureLoaded()
        val flat = flatten(input.trainSet)
        val cardIds = input.cardIds?.toLongArray() ?: LongArray(0)
        val values = withCancellation(cancellation) { token ->
            callNative {
                FsrsNative.optimize(
                    offsets = flat.offsets,
                    ratings = flat.ratings,
                    deltas = flat.deltas,
                    cardIds = cardIds,
                    enableShortTerm = input.enableShortTerm,
                    numRelearningSteps = input.numRelearningSteps ?: -1,
                    cancelToken = token,
                )
            }
        }
        return values.map { it.toDouble() }
    }

    private fun ensureLoaded() {
        try {
            FsrsNative.ensureLoaded()
        } catch (error: Throwable) {
            throw FsrsEngineException("NativeUnavailable", "FSRS native engine is unavailable", error)
        }
    }

    private fun nextState(values: FloatArray, offset: Int): FsrsNextState =
        FsrsNextState(
            memory = FsrsMemory(values[offset].toDouble(), values[offset + 1].toDouble()),
            intervalDays = values[offset + 2].toDouble(),
        )

    private fun List<Double>.toNativeFloats(): FloatArray =
        FloatArray(size) { index -> this[index].toFloat() }

    private fun flatten(items: List<FsrsTrainingItem>): FlatItems {
        val offsets = IntArray(items.size + 1)
        var count = 0
        for ((index, item) in items.withIndex()) {
            offsets[index] = count
            count += item.reviews.size
        }
        offsets[items.size] = count
        val ratings = IntArray(count)
        val deltas = LongArray(count)
        var cursor = 0
        for (item in items) {
            for (review in item.reviews) {
                ratings[cursor] = review.rating
                deltas[cursor] = review.deltaTDays
                cursor += 1
            }
        }
        return FlatItems(offsets, ratings, deltas)
    }

    private fun <T> withCancellation(
        cancellation: FsrsCancellation,
        block: (Long) -> T,
    ): T {
        if (cancellation.isCancelled()) throw interrupted()
        val token = FsrsNative.createCancelToken()
        val finished = AtomicBoolean(false)
        val watcher = Thread({
            try {
                while (!finished.get()) {
                    if (cancellation.isCancelled()) {
                        FsrsNative.cancelToken(token)
                        return@Thread
                    }
                    Thread.sleep(10)
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }, "dstu-fsrs-cancel")
        watcher.isDaemon = true
        watcher.start()
        return try {
            block(token).also { if (cancellation.isCancelled()) throw interrupted() }
        } finally {
            finished.set(true)
            watcher.interrupt()
            joinWatcher(watcher)
            FsrsNative.destroyCancelToken(token)
        }
    }

    private fun joinWatcher(watcher: Thread) {
        while (true) {
            try {
                watcher.join()
                return
            } catch (_: InterruptedException) {
                // The caller may already be cancelled; token ownership still requires the join.
                Thread.currentThread().interrupt()
            }
        }
    }

    private fun <T> callNative(block: () -> T): T = try {
        block()
    } catch (error: FsrsEngineException) {
        throw error
    } catch (error: IllegalArgumentException) {
        throw mapFailure(error)
    } catch (error: IllegalStateException) {
        throw mapFailure(error)
    }

    private fun mapFailure(error: RuntimeException): FsrsEngineException {
        val kind = error.message?.takeIf { it in KINDS }
        return FsrsEngineException(kind ?: "NativeFailure", error.message ?: "FSRS native failure", error)
    }

    private fun interrupted(): FsrsEngineException =
        FsrsEngineException("Interrupted", "FSRS engine interrupted")

    private data class FlatItems(
        val offsets: IntArray,
        val ratings: IntArray,
        val deltas: LongArray,
    )

    private companion object {
        val KINDS = setOf(
            "NotEnoughData",
            "Interrupted",
            "InvalidParameters",
            "OptimalNotFound",
            "InvalidInput",
            "InvalidDeckSize",
        )
    }
}

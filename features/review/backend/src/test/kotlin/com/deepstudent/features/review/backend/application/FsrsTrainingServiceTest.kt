package com.deepstudent.features.review.backend.application

import com.deepstudent.features.review.api.FsrsTrainer
import com.deepstudent.services.engine.api.fsrs.FsrsTrainingItem
import com.deepstudent.features.review.api.FsrsParameterStore
import com.deepstudent.features.review.api.FsrsTrainingCommit
import com.deepstudent.features.review.api.FsrsTrainingRequest
import com.deepstudent.features.review.api.FsrsTrainingResult
import com.deepstudent.features.review.api.FsrsTrainingStatus
import com.deepstudent.services.engine.api.fsrs.FsrsCancellation
import com.deepstudent.services.engine.api.fsrs.FsrsModelEvaluation
import com.deepstudent.services.engine.api.fsrs.FsrsReviewInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FsrsTrainingServiceTest {
    private val original = listOf(0.212, 1.2931, 2.3065)
    private val improved = listOf(0.9, 5.7, 10.2)

    @Test
    fun optimizedParametersAreCommittedOnce() {
        val store = MemoryStore(original)
        val service = FsrsTrainingService(
            trainer = FakeTrainer(FsrsTrainingStatus.OPTIMIZED, improved),
            store = store,
        )
        val result = service.train(request(), original, 123L)
        assertEquals(FsrsTrainingStatus.OPTIMIZED, result.status)
        assertEquals(improved, store.value)
        assertEquals(1, store.writes)
        assertEquals(123L, store.optimizedAtMs)
    }

    @Test
    fun cancelledOrUnimprovedResultsDoNotWriteParameters() {
        for (status in listOf(FsrsTrainingStatus.CANCELLED, FsrsTrainingStatus.ALREADY_OPTIMAL)) {
            val store = MemoryStore(original)
            val service = FsrsTrainingService(FakeTrainer(status, original), store)
            val result = service.train(request(), original, 123L)
            assertEquals(status, result.status)
            assertEquals(original, store.value)
            assertEquals(0, store.writes)
        }
    }

    @Test
    fun requestCarriesCancellationToTrainer() {
        val token = FsrsCancellation { true }
        val trainer = object : FsrsTrainer {
            override fun train(request: FsrsTrainingRequest): FsrsTrainingResult {
                assertTrue(request.cancellation.isCancelled())
                return FsrsTrainingResult(
                    FsrsTrainingStatus.CANCELLED,
                    1,
                    request.items.size,
                    2,
                    null,
                    null,
                    request.currentParams,
                )
            }
        }
        val store = MemoryStore(original)
        val result = FsrsTrainingService(trainer, store).train(
            request().copy(cancellation = token),
            original,
            1L,
        )
        assertEquals(FsrsTrainingStatus.CANCELLED, result.status)
        assertEquals(0, store.writes)
    }

    @Test fun lateCancellationDoesNotCommitAnAlreadyProducedResult() {
        var cancelled = false
        val store = MemoryStore(original)
        val trainer = FsrsTrainer { request ->
            cancelled = true
            FakeTrainer(FsrsTrainingStatus.OPTIMIZED, improved).train(request)
        }
        val result = FsrsTrainingService(trainer, store).train(
            request().copy(cancellation = FsrsCancellation { cancelled }), original, 1L)
        assertEquals(FsrsTrainingStatus.CANCELLED, result.status)
        assertEquals(original, store.value)
        assertEquals(0, store.writes)
    }

    @Test fun concurrentParameterChangeRejectsStaleTrainingCommit() {
        val store = MemoryStore(original)
        val external = listOf(99.0)
        val trainer = FsrsTrainer { request ->
            store.value = external
            FakeTrainer(FsrsTrainingStatus.OPTIMIZED, improved).train(request)
        }
        val result = FsrsTrainingService(trainer, store).train(request(), original, 1L)
        assertEquals(FsrsTrainingStatus.CONFLICT, result.status)
        assertEquals(external, store.value)
        assertEquals(0, store.writes)
    }

    private fun request() = FsrsTrainingRequest(
        currentParams = original,
        items = listOf(
            FsrsTrainingItem(listOf(FsrsReviewInput(3, 0), FsrsReviewInput(3, 1))),
        ),
        cardIds = listOf(1L),
        cardCount = 1,
        reviewCount = 2,
    )

    private class FakeTrainer(
        private val status: FsrsTrainingStatus,
        private val params: List<Double>,
    ) : FsrsTrainer {
        override fun train(request: FsrsTrainingRequest): FsrsTrainingResult =
            FsrsTrainingResult(
                status = status,
                cardCount = request.cardCount,
                itemCount = request.items.size,
                reviewCount = request.reviewCount,
                current = FsrsModelEvaluation(0.5, 0.2),
                optimized = FsrsModelEvaluation(0.4, 0.18),
                params = params,
            )
    }

    private class MemoryStore(initial: List<Double>) : FsrsParameterStore {
        var value = initial
        var writes = 0
        var optimizedAtMs: Long? = null

        override fun commit(expected: List<Double>, update: FsrsTrainingCommit): Boolean {
            if (value != expected) return false
            value = update.params
            writes += 1
            this.optimizedAtMs = update.optimizedAtMs
            return true
        }
    }
}

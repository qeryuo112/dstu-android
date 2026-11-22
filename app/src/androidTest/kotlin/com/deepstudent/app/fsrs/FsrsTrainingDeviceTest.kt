package com.deepstudent.app.fsrs

import com.deepstudent.engines.fsrs.FsrsNumericEngineAdapter
import com.deepstudent.features.review.api.*
import com.deepstudent.features.review.backend.application.FsrsTrainingService
import com.deepstudent.features.review.backend.application.NativeFsrsTrainer
import com.deepstudent.services.engine.api.fsrs.*
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test

class FsrsTrainingDeviceTest {
    @Test fun realTrainingProducesParametersAndLossMatchingFrozenEngine() {
        val fixture = fsrsFixture("training-vectors.json")
        val engine = FsrsNumericEngineAdapter()
        val items = fixture.trainingItems()
        val current = engine.evaluate(emptyList(), items)
        assertEquals(fixture.getJSONObject("current").getDouble("logLoss"), current.logLoss, 1e-5)
        val params = engine.optimize(FsrsComputeParametersInput(items, fixture.getJSONArray("cardIds").longs(), true, null))
        val expected = fixture.getJSONArray("params").doubles()
        assertEquals(expected.size, params.size)
        params.forEachIndexed { index, value -> assertEquals("parameter $index", expected[index], value, 1e-4) }
        val optimized = engine.evaluate(params, items)
        assertEquals(fixture.getJSONObject("optimized").getDouble("logLoss"), optimized.logLoss, 1e-5)
        assertEquals(fixture.getJSONObject("optimized").getDouble("rmseBins"), optimized.rmseBins, 1e-5)
        assertTrue(optimized.logLoss < current.logLoss)
        println("s1.fsrs.training items=${items.size} parameters=${params.size} currentLoss=${current.logLoss} optimizedLoss=${optimized.logLoss}")
    }

    @Test fun cancelledTrainingLeavesOriginalParametersUntouched() {
        val fixture = fsrsFixture("training-vectors.json")
        val engine = FsrsNumericEngineAdapter()
        val original = engine.normalizeParameters(emptyList())
        var writes = 0
        val store = FsrsParameterStore { _, _ -> writes++; true }
        val request = FsrsTrainingRequest(original, fixture.trainingItems(), fixture.getJSONArray("cardIds").longs(),
            cardCount = 32, reviewCount = 128, cancellation = FsrsCancellation { true })
        val result = FsrsTrainingService(NativeFsrsTrainer(engine), store).train(request, original, 1)
        assertEquals(FsrsTrainingStatus.CANCELLED, result.status)
        assertEquals(original, result.params)
        assertEquals(0, writes)
    }

    @Test fun nativeCancellationCanInterruptActualOptimizer() {
        val engine = FsrsNumericEngineAdapter()
        val fixture = fsrsFixture("training-vectors.json")
        val items = fixture.trainingItems()
        val cancelled = AtomicBoolean(false)
        val request = FsrsComputeParametersInput(List(3000) { items }.flatten(), null, true, null)
        val started = System.nanoTime()
        val thread = Thread { Thread.sleep(10); cancelled.set(true) }
        thread.start()
        try {
            engine.optimize(request, FsrsCancellation { cancelled.get() })
            fail("native optimizer ignored cancellation")
        } catch (failure: FsrsEngineException) {
            assertEquals("Interrupted", failure.kind)
            assertTrue("cancellation did not remain bounded", (System.nanoTime() - started) / 1_000_000 < 10_000)
        }
        finally { thread.join(2000) }
    }
}

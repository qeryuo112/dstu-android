package com.deepstudent.features.review.backend.domain

import com.deepstudent.features.review.api.FsrsCardSnapshot
import com.deepstudent.features.review.api.FsrsDelay
import com.deepstudent.features.review.api.FsrsPhase
import com.deepstudent.features.review.api.FsrsSchedulerConfig
import com.deepstudent.services.engine.api.fsrs.FsrsMemory
import com.deepstudent.services.engine.api.fsrs.FsrsNextState
import com.deepstudent.services.engine.api.fsrs.FsrsNextStates
import com.deepstudent.services.engine.api.fsrs.FsrsNumericEngine
import com.deepstudent.services.engine.api.fsrs.FsrsComputeParametersInput
import com.deepstudent.services.engine.api.fsrs.FsrsModelEvaluation
import com.deepstudent.services.engine.api.fsrs.FsrsReviewInput
import com.deepstudent.services.engine.api.fsrs.FsrsTrainingItem
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnkiFsrsSchedulerTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = 1_794_686_400_000L
    private val engine = ConstantEngine()
    private val scheduler = AnkiFsrsScheduler(engine, zone)

    @Test
    fun newCardUsesSharedLearningStepsAndMinutes() {
        val result = scheduler.scheduleAll(
            FsrsCardSnapshot("card-1", FsrsPhase.NEW, null, 0, 0, 0, null),
            now,
            FsrsSchedulerConfig(enableFuzz = false),
        )
        assertEquals(FsrsPhase.LEARNING, result.again.phase)
        assertEquals(FsrsDelay.Minutes(1.0), result.again.delay)
        assertEquals(FsrsDelay.Minutes(5.5), result.hard.delay)
        assertEquals(FsrsDelay.Minutes(10.0), result.good.delay)
        assertEquals(FsrsPhase.REVIEW, result.easy.phase)
        assertTrue(result.easy.delay is FsrsDelay.Days)
        assertEquals(1, result.good.reps)
    }

    @Test
    fun reviewAgainEntersRelearningAndCountsLapse() {
        val result = scheduler.scheduleAll(
            FsrsCardSnapshot(
                "card-1",
                FsrsPhase.REVIEW,
                FsrsMemory(10.0, 5.0),
                0,
                3,
                2,
                now - 10 * 86_400_000L,
            ),
            now,
            FsrsSchedulerConfig(enableFuzz = false),
        )
        assertEquals(FsrsPhase.RELEARNING, result.again.phase)
        assertEquals(FsrsDelay.Minutes(10.0), result.again.delay)
        assertEquals(3, result.again.lapses)
        assertEquals(2, result.good.lapses)
        assertEquals(FsrsPhase.REVIEW, result.good.phase)
    }

    @Test
    fun fuzzIsDeterministicAndMaximumIntervalCapsPassingAnswers() {
        val card = FsrsCardSnapshot(
            "card-1",
            FsrsPhase.REVIEW,
            FsrsMemory(400.0, 5.0),
            0,
            4,
            0,
            now - 400 * 86_400_000L,
        )
        val first = scheduler.scheduleAll(card, now, FsrsSchedulerConfig(maximumInterval = 2))
        val second = scheduler.scheduleAll(card, now, FsrsSchedulerConfig(maximumInterval = 2))
        assertEquals(first.good.delay, second.good.delay)
        assertEquals(FsrsDelay.Days(2), first.good.delay)
        assertEquals(FsrsDelay.Days(2), first.easy.delay)
    }

    @Test
    fun logicalDayUsesRolloverAndLearningStepCrossesToNextDayStart() {
        val rollover = ZonedDateTime.of(2026, 10, 2, 4, 0, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals("2026-10-01", FsrsLogicalTime.dayKey(rollover - 30 * 60_000L, 4, zone))
        assertEquals("2026-10-02", FsrsLogicalTime.dayKey(rollover + 30 * 60_000L, 4, zone))
        val late = rollover + 19 * 3_600_000L
        assertEquals(
            FsrsLogicalTime.bounds(late, 4, zone).second,
            FsrsLogicalTime.learningDueMs(late, 360.0, 4, zone),
        )
    }

    private class ConstantEngine : FsrsNumericEngine {
        override fun nextStates(
            parameters: List<Double>,
            memory: FsrsMemory?,
            desiredRetention: Double,
            daysElapsed: Long,
        ): FsrsNextStates = FsrsNextStates(
            FsrsNextState(FsrsMemory(0.212, 6.4133), 0.212),
            FsrsNextState(FsrsMemory(1.2931, 5.1121707), 1.2931),
            FsrsNextState(FsrsMemory(2.3065, 2.118104), 2.3065),
            FsrsNextState(FsrsMemory(8.2956, 1.0), 8.2956),
        )

        override fun memoryState(
            parameters: List<Double>,
            reviews: List<FsrsReviewInput>,
            startingState: FsrsMemory?,
        ): FsrsMemory = FsrsMemory(1.0, 5.0)

        override fun normalizeParameters(parameters: List<Double>): List<Double> = parameters

        override fun evaluate(
            parameters: List<Double>,
            items: List<FsrsTrainingItem>,
            cancellation: com.deepstudent.services.engine.api.fsrs.FsrsCancellation,
        ): FsrsModelEvaluation = FsrsModelEvaluation(0.5, 0.2)

        override fun optimize(
            input: FsrsComputeParametersInput,
            cancellation: com.deepstudent.services.engine.api.fsrs.FsrsCancellation,
        ): List<Double> = emptyList()
    }
}

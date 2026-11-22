package com.deepstudent.features.review.backend.domain

import com.deepstudent.features.review.api.FsrsCardSnapshot
import com.deepstudent.features.review.api.FsrsDelay
import com.deepstudent.features.review.api.FsrsFourRatings
import com.deepstudent.features.review.api.FsrsPhase
import com.deepstudent.features.review.api.FsrsReviewScheduler
import com.deepstudent.features.review.api.FsrsScheduleOutcome
import com.deepstudent.features.review.api.FsrsSchedulerConfig
import com.deepstudent.features.review.api.FsrsReviewCounts
import com.deepstudent.features.review.api.MS_PER_DAY
import com.deepstudent.services.engine.api.fsrs.FsrsMemory
import com.deepstudent.services.engine.api.fsrs.FsrsNextState
import com.deepstudent.services.engine.api.fsrs.FsrsNextStates
import com.deepstudent.services.engine.api.fsrs.FsrsNumericEngine
import java.time.ZoneId
import kotlin.math.max
import kotlin.math.min

private data class Answer(
    val phase: FsrsPhase,
    val memory: FsrsMemory,
    val learningStep: Int,
    val delay: FsrsDelay,
    val reps: Int,
    val lapses: Int,
)

private data class StepContext(
    val config: FsrsSchedulerConfig,
    val steps: List<Double>,
    val phase: FsrsPhase,
    val fuzzFactor: Double?,
)

class AnkiFsrsScheduler(
    private val engine: FsrsNumericEngine,
    private val zone: ZoneId = ZoneId.systemDefault(),
) : FsrsReviewScheduler {
    override fun scheduleAll(
        card: FsrsCardSnapshot,
        nowMs: Long,
        config: FsrsSchedulerConfig,
    ): FsrsFourRatings {
        val memory = usableMemory(card)
        val lastReviewMs = card.lastReviewMs
        val daysElapsed = if (memory != null && lastReviewMs != null) {
            FsrsLogicalTime.daysBetween(lastReviewMs, nowMs, config.dayRolloverHour, zone)
        } else {
            0L
        }
        val next = engine.nextStates(
            config.fsrsParams,
            memory,
            config.desiredRetention,
            daysElapsed,
        )
        val reps = if (card.reps == Int.MAX_VALUE) Int.MAX_VALUE else card.reps + 1
        val fuzzFactor = if (config.enableFuzz) FsrsFuzz.factorFor(card.id, card.reps) else null
        val answers = selectAnswers(card, config, next, reps, daysElapsed, fuzzFactor, memory)
        return FsrsFourRatings(
            toOutcome(answers.again, nowMs, config, daysElapsed),
            toOutcome(answers.hard, nowMs, config, daysElapsed),
            toOutcome(answers.good, nowMs, config, daysElapsed),
            toOutcome(answers.easy, nowMs, config, daysElapsed),
        )
    }

    private fun selectAnswers(
        card: FsrsCardSnapshot,
        config: FsrsSchedulerConfig,
        next: FsrsNextStates,
        reps: Int,
        daysElapsed: Long,
        fuzzFactor: Double?,
        memory: FsrsMemory?,
    ): FourAnswers {
        if (card.phase == FsrsPhase.REVIEW && memory != null) {
            return reviewAnswers(card, config, next, reps, daysElapsed, fuzzFactor)
        }
        val phase = if (card.phase == FsrsPhase.RELEARNING && memory != null) {
            FsrsPhase.RELEARNING
        } else {
            FsrsPhase.LEARNING
        }
        val steps = if (phase == FsrsPhase.RELEARNING) config.relearningSteps else config.learningSteps
        return steppedAnswers(
            card,
            next,
            reps,
            StepContext(config, FsrsFuzz.sanitizeSteps(steps), phase, fuzzFactor),
        )
    }

    private fun usableMemory(card: FsrsCardSnapshot): FsrsMemory? =
        if (card.phase == FsrsPhase.NEW) null else card.memory?.takeIf {
            it.stability > 0 && it.difficulty > 0
        }

    private fun reviewAnswers(
        card: FsrsCardSnapshot,
        config: FsrsSchedulerConfig,
        next: FsrsNextStates,
        reps: Int,
        daysElapsed: Long,
        fuzzFactor: Double?,
    ): FourAnswers {
        val lapses = if (card.lapses == Int.MAX_VALUE) Int.MAX_VALUE else card.lapses + 1
        val relearning = FsrsFuzz.sanitizeSteps(config.relearningSteps)
        val again = if (relearning.isNotEmpty()) {
            Answer(
                phase = FsrsPhase.RELEARNING,
                memory = next.again.memory,
                learningStep = 0,
                delay = FsrsDelay.Minutes(relearning[0]),
                reps = reps,
                lapses = lapses,
            )
        } else {
            Answer(
                phase = FsrsPhase.REVIEW,
                memory = next.again.memory,
                learningStep = 0,
                delay = FsrsDelay.Days(constrain(config, next.again.intervalDays, 1, 0, fuzzFactor)),
                reps = reps,
                lapses = lapses,
            )
        }
        val hardDays = constrain(config, next.hard.intervalDays, 1, daysElapsed, fuzzFactor)
        val goodDays = constrain(config, next.good.intervalDays, hardDays + 1, daysElapsed, fuzzFactor)
        val easyDays = constrain(config, next.easy.intervalDays, goodDays + 1, daysElapsed, fuzzFactor)
        return FourAnswers(
            again,
            passing(next.hard.memory, hardDays, reps, card.lapses),
            passing(next.good.memory, goodDays, reps, card.lapses),
            passing(next.easy.memory, easyDays, reps, card.lapses),
        )
    }

    private fun steppedAnswers(
        card: FsrsCardSnapshot,
        next: FsrsNextStates,
        reps: Int,
        ctx: StepContext,
    ): FourAnswers {
        val isNew = card.phase == FsrsPhase.NEW || card.memory == null
        val lastStep = max(0, ctx.steps.size - 1)
        val index = if (isNew) 0 else min(card.learningStep, lastStep)
        val (goodDays, easyDays) = graduation(ctx.config, next, ctx.fuzzFactor)
        val easy = passing(next.easy.memory, easyDays, reps, card.lapses)
        if (ctx.steps.isEmpty()) return emptySteps(ctx, next, reps, card.lapses, goodDays, easy)
        val again = stepping(ctx, next.again.memory, 0, ctx.steps[0], reps, card.lapses)
        val hard = stepping(ctx, next.hard.memory, index, FsrsFuzz.hardDelay(ctx.steps, index), reps, card.lapses)
        val goodStep = if (isNew) 1 else index + 1
        val good = if (goodStep < ctx.steps.size) {
            stepping(ctx, next.good.memory, goodStep, ctx.steps[goodStep], reps, card.lapses)
        } else {
            passing(next.good.memory, goodDays, reps, card.lapses)
        }
        return FourAnswers(again, hard, good, easy)
    }

    private fun emptySteps(
        ctx: StepContext,
        next: FsrsNextStates,
        reps: Int,
        lapses: Int,
        goodDays: Int,
        easy: Answer,
    ): FourAnswers {
        val again = shortOrReview(ctx, next.again, 1, reps, lapses)
        val hard = shortOrReview(ctx, next.hard, 1, reps, lapses)
        val cappedHard = if (hard.delay is FsrsDelay.Days) {
            hard.copy(delay = FsrsDelay.Days(min((hard.delay as FsrsDelay.Days).days, goodDays)))
        } else {
            hard
        }
        val good = passing(next.good.memory, goodDays, reps, lapses)
        return FourAnswers(again, cappedHard, good, easy)
    }

    private fun shortOrReview(
        ctx: StepContext,
        state: FsrsNextState,
        minimumDays: Int,
        reps: Int,
        lapses: Int,
    ): Answer = if (state.intervalDays < 1) {
        Answer(
            ctx.phase,
            state.memory,
            0,
            FsrsDelay.Minutes(max(state.intervalDays * 1_440.0, 1.0)),
            reps,
            lapses,
        )
    } else {
        passing(
            state.memory,
            constrain(ctx.config, state.intervalDays, minimumDays, 0, ctx.fuzzFactor),
            reps,
            lapses,
        )
    }

    private fun graduation(
        config: FsrsSchedulerConfig,
        next: FsrsNextStates,
        fuzzFactor: Double?,
    ): Pair<Int, Int> {
        val good = constrain(config, next.good.intervalDays, 1, 0, fuzzFactor)
        val easy = constrain(config, next.easy.intervalDays, good + 1, 0, fuzzFactor)
        return good to easy
    }

    private fun stepping(
        ctx: StepContext,
        memory: FsrsMemory,
        step: Int,
        minutes: Double,
        reps: Int,
        lapses: Int,
    ): Answer = Answer(ctx.phase, memory, step, FsrsDelay.Minutes(minutes), reps, lapses)

    private fun passing(memory: FsrsMemory, days: Int, reps: Int, lapses: Int): Answer =
        Answer(FsrsPhase.REVIEW, memory, 0, FsrsDelay.Days(days), reps, lapses)

    private fun constrain(
        config: FsrsSchedulerConfig,
        interval: Double,
        minimum: Int,
        daysElapsed: Long,
        fuzzFactor: Double?,
    ): Int {
        val maximum = max(1, config.maximumInterval)
        var minValue = max(1, minimum).toLong()
        if (fuzzFactor != null && interval > daysElapsed) minValue = max(minValue, daysElapsed + 1)
        return FsrsFuzz.withReviewFuzz(
            fuzzFactor,
            interval,
            minValue.coerceAtMost(maximum.toLong()).toInt(),
            maximum,
        )
    }

    private fun toOutcome(
        answer: Answer,
        nowMs: Long,
        config: FsrsSchedulerConfig,
        daysElapsed: Long,
    ): FsrsScheduleOutcome {
        val dueMs: Long
        val scheduledDays: Int
        when (val delay = answer.delay) {
            is FsrsDelay.Minutes -> {
                dueMs = FsrsLogicalTime.learningDueMs(nowMs, delay.minutes, config.dayRolloverHour, zone)
                scheduledDays = 0
            }
            is FsrsDelay.Days -> {
                dueMs = nowMs + delay.days * MS_PER_DAY
                scheduledDays = delay.days
            }
        }
        return FsrsScheduleOutcome(
            answer.phase,
            answer.memory,
            answer.delay,
            scheduledDays,
            daysElapsed,
            dueMs,
            FsrsReviewCounts(answer.reps, answer.lapses),
            answer.learningStep,
        )
    }

    private data class FourAnswers(
        val again: Answer,
        val hard: Answer,
        val good: Answer,
        val easy: Answer,
    )
}

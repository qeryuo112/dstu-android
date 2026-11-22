package com.deepstudent.app.fsrs

import com.deepstudent.engines.fsrs.FsrsNumericEngineAdapter
import com.deepstudent.features.review.api.*
import com.deepstudent.features.review.backend.domain.AnkiFsrsScheduler
import com.deepstudent.features.review.backend.domain.ReviewPlanFsrsAdapter
import com.deepstudent.services.engine.api.fsrs.FsrsMemory
import java.time.ZoneId
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class FsrsSchedulerOracleDeviceTest {
    private val engine = FsrsNumericEngineAdapter()
    private val scheduler = AnkiFsrsScheduler(engine, ZoneId.of("Asia/Shanghai"))

    @Test fun flashcardFourRatingsAndMinuteDayDueMatchFrozenDesktop() {
        val vectors = fsrsFixture("scheduler-vectors.json")
        for (case in vectors.getJSONArray("cases").objects()) {
            val config = case.getJSONObject("config")
            val card = card(case.getJSONObject("card"), case.getString("id"))
            val result = scheduler.scheduleAll(card, case.getLong("nowMs"), FsrsSchedulerConfig(
                desiredRetention = config.getDouble("desiredRetention"),
                learningSteps = config.getJSONArray("learningSteps").doubles(),
                relearningSteps = config.getJSONArray("relearningSteps").doubles(),
                maximumInterval = config.getInt("maximumInterval"), enableFuzz = !config.isNull("fuzzFactor"),
            ))
            val expected = case.getJSONObject("expected")
            for ((rating, actual) in listOf("again" to result.again, "hard" to result.hard,
                "good" to result.good, "easy" to result.easy)) {
                compareOutcome("${case.getString("id")}.$rating", actual, expected.getJSONObject(rating))
            }
        }
    }

    @Test fun questionPlanAndLegacySm2LazyConversionMatchAllFourRatings() {
        val adapter = ReviewPlanFsrsAdapter(scheduler, ZoneId.of("Asia/Shanghai"), FsrsSchedulerConfig())
        for (case in fsrsFixture("scheduler-vectors.json").getJSONArray("planCases").objects()) {
            val plan = plan(case.getJSONObject("plan"))
            val now = case.getLong("nowMs")
            comparePlanState(adapter.resolveState(plan, null, now), case.getJSONObject("state"))
            val expected = case.getJSONArray("outcomes").objects()
            val actual = adapter.scheduleAll(plan, null, now)
            assertEquals(4, actual.size)
            actual.forEachIndexed { index, outcome ->
                val value = expected[index]
                assertEquals(value.getInt("rating"), outcome.rating.value)
                assertEquals(value.getBoolean("passed"), outcome.passed)
                assertEquals(value.getInt("intervalDays"), outcome.intervalDays)
                assertEquals(value.getLong("intervalMinutes"), outcome.intervalMinutes)
                assertEquals(value.getString("nextReviewDate"), outcome.nextReviewDate)
                assertEquals(value.getDouble("easeFactor"), outcome.easeFactor, 1e-6)
                comparePlanState(outcome.fsrs, value.getJSONObject("fsrs"))
            }
        }
    }

    private fun compareOutcome(label: String, actual: FsrsScheduleOutcome, expected: JSONObject) {
        assertEquals(label, expected.getString("phase"), actual.phase.wire)
        assertEquals(label, expected.getDouble("stability"), actual.memory.stability, 1e-5)
        assertEquals(label, expected.getDouble("difficulty"), actual.memory.difficulty, 1e-5)
        val delay = expected.getJSONObject("delay")
        when (val value = actual.delay) {
            is FsrsDelay.Minutes -> { assertEquals("minutes", delay.getString("kind")); assertEquals(delay.getDouble("minutes"), value.minutes, 1e-5) }
            is FsrsDelay.Days -> { assertEquals("days", delay.getString("kind")); assertEquals(delay.getInt("days"), value.days) }
        }
        assertEquals(label, expected.getLong("dueMs"), actual.dueMs)
        assertEquals(label, expected.getInt("scheduledDays"), actual.scheduledDays)
        assertEquals(label, expected.getInt("learningStep"), actual.learningStep)
        assertEquals(label, expected.getInt("reps"), actual.reps)
        assertEquals(label, expected.getInt("lapses"), actual.lapses)
    }

    private fun card(value: JSONObject, fallback: String): FsrsCardSnapshot = FsrsCardSnapshot(
        id = value.optString("id", fallback), phase = FsrsPhase.fromWire(value.getString("phase"))!!,
        memory = value.optJSONObject("memory")?.let { FsrsMemory(it.getDouble("stability"), it.getDouble("difficulty")) },
        learningStep = value.getInt("learningStep"), reps = value.getInt("reps"), lapses = value.getInt("lapses"),
        lastReviewMs = value.optionalLong("lastReviewMs"),
    )

    private fun plan(value: JSONObject) = ReviewPlanSnapshot(value.getString("id"), value.getString("question_id"),
        value.getString("exam_id"), ReviewPlanLegacySchedule(value.getDouble("ease_factor"), value.getInt("interval_days"),
            value.getInt("repetitions"), value.getString("next_review_date"), value.optionalString("last_review_date"), value.getString("status")),
        ReviewPlanStatistics(value.getInt("total_reviews"), value.getInt("total_correct"), value.getInt("consecutive_failures"),
            value.getBoolean("is_difficult")), FsrsRowTimes(value.getString("created_at"), value.getString("updated_at")))

    private fun comparePlanState(actual: ReviewPlanFsrsState, expected: JSONObject) {
        assertEquals(expected.optionalString("phase"), actual.phase?.wire)
        assertEquals(expected.getInt("learning_step"), actual.learningStep)
        assertEquals(expected.getInt("lapses"), actual.lapses)
        assertEquals(expected.optionalLong("due_at"), actual.dueAt)
        assertEquals(expected.optionalLong("last_review_at"), actual.lastReviewAt)
        val stability = expected.optionalDouble("stability")
        val difficulty = expected.optionalDouble("difficulty")
        if (stability == null) assertNull(actual.stability) else assertEquals(stability, actual.stability!!, 1e-5)
        if (difficulty == null) assertNull(actual.difficulty) else assertEquals(difficulty, actual.difficulty!!, 1e-5)
    }
}

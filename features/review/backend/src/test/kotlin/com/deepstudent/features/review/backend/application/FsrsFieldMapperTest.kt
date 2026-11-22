package com.deepstudent.features.review.backend.application

import com.deepstudent.features.review.api.FsrsCardStateRow
import com.deepstudent.features.review.api.FsrsDelay
import com.deepstudent.features.review.api.FsrsPhase
import com.deepstudent.features.review.api.FsrsRating
import com.deepstudent.features.review.api.FsrsScheduleOutcome
import com.deepstudent.services.engine.api.fsrs.FsrsMemory
import com.deepstudent.features.review.api.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FsrsFieldMapperTest {
    private val row = FsrsCardStateRow(
        id = "state-1",
        ankiCardId = "anki-1",
        deckId = "deck_default",
        schedule = FsrsCardScheduleColumns(FsrsPhase.NEW, null, null, 0.0, 0.0,
            FsrsReviewCounts(0, 0), FsrsDueColumns(100L, null), 0),
        policy = FsrsCardPolicyColumns(false, "fsrs-rs-6.6", 0.9, false, null),
        times = FsrsRowTimes("2026-11-01T00:00:00Z", "2026-11-01T00:00:00Z"),
    )
    private val outcome = FsrsScheduleOutcome(
        phase = FsrsPhase.LEARNING,
        memory = FsrsMemory(2.3065, 2.118104),
        delay = FsrsDelay.Minutes(10.0),
        scheduledDays = 0,
        elapsedDays = 0,
        dueMs = 1_000L,
        counts = FsrsReviewCounts(1, 0),
        learningStep = 1,
    )

    @Test
    fun snapshotRejectsNullMemoryAndPreservesIdentity() {
        val snapshot = FsrsFieldMapper.snapshot(row)
        assertEquals("state-1", snapshot.id)
        assertEquals(FsrsPhase.NEW, snapshot.phase)
        assertNull(snapshot.memory)
        assertNull(snapshot.lastReviewMs)
    }

    @Test
    fun applyOutcomeMapsSchedulingColumnsWithoutMixingLogColumns() {
        val mapped = FsrsFieldMapper.applyOutcome(
            row,
            outcome,
            2_000L,
            "2026-11-15T04:00:00Z",
            "fsrs-rs-6.6",
            0.9,
        )
        assertEquals(FsrsPhase.LEARNING, mapped.phase)
        assertEquals(2.3065, mapped.stability!!, 0.0)
        assertEquals(1, mapped.learningStep)
        assertEquals(2_000L, mapped.lastReviewMs)
        assertEquals(1_000L, mapped.dueMs)
        assertEquals("state-1", mapped.id)
    }

    @Test
    fun reviewLogRecordsBeforeAndAfterSeparately() {
        val log = FsrsFieldMapper.reviewLog(
            "log-1",
            row,
            outcome,
            FsrsRating.GOOD,
            2_000L,
            500L,
            "fsrs-rs-6.6",
        )
        assertNull(log.stabilityBefore)
        assertEquals(2.3065, log.stabilityAfter!!, 0.0)
        assertEquals(FsrsPhase.NEW, log.stateBefore)
        assertEquals(FsrsPhase.LEARNING, log.stateAfter)
        assertEquals(100L, log.dueBeforeMs)
        assertEquals(1_000L, log.dueAfterMs)
        assertEquals(500L, log.durationMs)
    }
}

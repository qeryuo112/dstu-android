package com.deepstudent.features.review.backend.application

import com.deepstudent.features.review.api.FsrsHistoryRow
import com.deepstudent.features.review.api.FsrsMemoryColumns
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class FsrsTrainingSetBuilderTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val start = 1_794_672_000_000L

    @Test
    fun onlyCrossDayPrefixesBecomeItems() {
        val rows = listOf(
            row("s1", 1, start, 0, null),
            row("s1", 3, start + 86_400_000L, 1, 0.0),
            row("s1", 4, start + 2 * 86_400_000L, 1, 1.0),
        )
        val set = FsrsTrainingSetBuilder.build(rows, 4, zone)
        assertEquals(2, set.items.size)
        assertEquals(2, set.items[0].reviews.size)
        assertEquals(3, set.items[1].reviews.size)
        assertEquals(listOf(0L, 0L), set.cardIds)
        assertEquals(1, set.cardCount)
        assertEquals(3, set.reviewCount)
    }

    @Test
    fun sameLogicalDayPrefixIsNotATrainingItem() {
        val rows = listOf(
            row("s1", 1, start, 0, null),
            row("s1", 3, start + 60_000L, 1, 0.0),
        )
        assertEquals(0, FsrsTrainingSetBuilder.build(rows, 4, zone).items.size)
    }

    @Test
    fun truncatedHistoryKeepsStartingStateAndDoesNotBecomeTrainingData() {
        val rows = listOf(
            row("s1", 3, start, 2, 1.0, stability = 12.0, difficulty = 5.0),
            row("s1", 4, start + 86_400_000L, 2, 1.0, stability = 12.0, difficulty = 5.0),
        )
        val set = FsrsTrainingSetBuilder.build(rows, 4, zone)
        assertEquals(0, set.items.size)
        assertEquals(0, set.cardCount)
    }

    private fun row(
        id: String,
        rating: Int,
        reviewMs: Long,
        stateBefore: Int,
        elapsedDays: Double?,
        stability: Double? = null,
        difficulty: Double? = null,
    ) = FsrsHistoryRow(
        cardStateId = id,
        ankiCardId = "anki-$id",
        rating = rating,
        reviewMs = reviewMs,
        stateBefore = stateBefore,
        memoryBefore = FsrsMemoryColumns(stability, difficulty),
        elapsedDays = elapsedDays,
        snapshotLastReviewMs = null,
    )
}

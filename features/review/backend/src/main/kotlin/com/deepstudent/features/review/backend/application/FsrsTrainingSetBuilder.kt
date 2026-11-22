package com.deepstudent.features.review.backend.application

import com.deepstudent.features.review.api.FsrsHistoryRow
import com.deepstudent.features.review.api.FsrsTrainingSet
import com.deepstudent.features.review.backend.domain.FsrsLogicalTime
import com.deepstudent.services.engine.api.fsrs.FsrsMemory
import com.deepstudent.services.engine.api.fsrs.FsrsReviewInput
import com.deepstudent.services.engine.api.fsrs.FsrsTrainingItem
import java.time.ZoneId
import kotlin.math.max
import kotlin.math.round

object FsrsTrainingSetBuilder {
    fun build(
        rows: List<FsrsHistoryRow>,
        rolloverHour: Int,
        zone: ZoneId = ZoneId.systemDefault(),
    ): FsrsTrainingSet {
        val histories = loadHistories(rows, rolloverHour, zone)
        val items = ArrayList<FsrsTrainingItem>()
        val cardIds = ArrayList<Long>()
        var cardCount = 0
        var reviewCount = 0
        histories.forEachIndexed { index, history ->
            if (history.starting != null) return@forEachIndexed
            var used = false
            for (end in 1 until history.reviews.size) {
                if (history.reviews[end].deltaTDays == 0L) continue
                items += FsrsTrainingItem(history.reviews.subList(0, end + 1))
                cardIds += index.toLong()
                used = true
            }
            if (used) {
                cardCount += 1
                reviewCount += history.reviews.size
            }
        }
        return FsrsTrainingSet(items, cardIds, cardCount, reviewCount)
    }

    private data class History(
        val starting: FsrsMemory?,
        val reviews: List<FsrsReviewInput>,
    )

    private data class Cursor(
        val cardStateId: String,
        val starting: FsrsMemory?,
        var previousMs: Long?,
        var valid: Boolean,
        val reviews: MutableList<FsrsReviewInput> = ArrayList(),
    )

    private fun loadHistories(
        rows: List<FsrsHistoryRow>,
        rolloverHour: Int,
        zone: ZoneId,
    ): List<History> {
        val result = ArrayList<History>()
        var current: Cursor? = null
        for (row in rows) {
            val cursor = current
            if (cursor != null && cursor.cardStateId == row.cardStateId) {
                append(cursor, row, rolloverHour, zone)
            } else {
                flush(cursor, result)
                current = newCursor(row).also { append(it, row, rolloverHour, zone) }
            }
        }
        flush(current, result)
        return result
    }

    private fun append(
        cursor: Cursor,
        row: FsrsHistoryRow,
        rolloverHour: Int,
        zone: ZoneId,
    ) {
        if (!cursor.valid || row.rating !in 1..4) return
        val previousMs = cursor.previousMs
        val delta = if (previousMs == null) {
            0L
        } else {
            FsrsLogicalTime.daysBetween(previousMs, row.reviewMs, rolloverHour, zone)
        }
        cursor.reviews += FsrsReviewInput(row.rating, delta)
        cursor.previousMs = row.reviewMs
    }

    private fun newCursor(row: FsrsHistoryRow): Cursor {
        if (row.stateBefore == 0) return Cursor(row.cardStateId, null, null, true)
        val stability = row.stabilityBefore
        val difficulty = row.difficultyBefore
        if (stability == null || difficulty == null || stability <= 0 || !stability.isFinite() || !difficulty.isFinite()) {
            return Cursor(row.cardStateId, null, null, false)
        }
        val snapshot = row.snapshotLastReviewMs
        val previous = snapshot ?: row.elapsedDays?.let {
            row.reviewMs - round(max(0.0, it)).toLong() * 86_400_000L
        }
        return Cursor(row.cardStateId, FsrsMemory(stability, difficulty), previous, true)
    }

    private fun flush(cursor: Cursor?, result: MutableList<History>) {
        if (cursor != null && cursor.valid && cursor.reviews.isNotEmpty()) {
            result += History(cursor.starting, cursor.reviews)
        }
    }
}

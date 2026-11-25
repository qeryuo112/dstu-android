package com.deepstudent.features.cards.data

import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.core.common.logging.Logger
import com.deepstudent.core.common.logging.NoopLogger
import com.deepstudent.features.cards.api.IncomingDeckSync
import com.deepstudent.platform.database.SqlDatabase
import com.deepstudent.platform.database.SqlValue
import java.util.concurrent.CancellationException

class AncillaryDeckApplier(
    private val database: SqlDatabase,
    private val logger: Logger = NoopLogger,
    private val checkCancelled: () -> Unit = ::deckCheckCancelled,
) : IncomingDeckSync {
    override fun apply(changes: List<ByteArray>) {
        val start = System.nanoTime()
        try {
            checkCancelled()
            val decoded = decodeIncoming(changes)
            if (decoded.isNotEmpty()) applyDecoded(decoded)
            report("complete", decoded.size, start)
        } catch (cancelled: CancellationException) {
            report("cancelled", changes.size, start)
            throw cancelled
        } catch (_: Exception) {
            report("failed", changes.size, start)
            throw IllegalStateException("Incoming deck sync failed")
        }
    }

    /** Reuse the domain parser before any owner in a cross-database batch commits. */
    fun validateIncoming(changes: List<ByteArray>) {
        decodeIncoming(changes)
    }

    private fun decodeIncoming(changes: List<ByteArray>): List<IncomingDeckChange> = changes.map {
        checkCancelled()
        IncomingDeckChange.parse(it)
    }

    private fun applyDecoded(changes: List<IncomingDeckChange>) {
        database.transaction {
            deckSetup()
            val max = query("SELECT COALESCE(MAX(id),0) FROM __change_log") { it.long(0) }.single()
            changes.forEach { change ->
                checkCancelled()
                deckApply(change)
            }
            checkCancelled()
            execute(
                "UPDATE __change_log SET sync_version=1 WHERE id>? AND table_name='anki_decks' AND COALESCE(sync_version,0)=0",
                listOf(SqlValue.Integer(max)),
            )
            checkCancelled()
        }
    }

    private fun report(status: String, count: Int, start: Long) {
        runCatching {
            logger.info(
                "incoming_deck_sync",
                JsonValue.Object.Builder().put("owner", "cards/data")
                    .put("phase", "apply").put("status", status).put("count", count)
                    .put("elapsedMs", (System.nanoTime() - start) / 1_000_000).build(),
            )
        }
    }
}

internal fun deckCheckCancelled() {
    if (Thread.currentThread().isInterrupted) throw CancellationException("Incoming deck sync cancelled")
}

internal fun text(value: String): SqlValue.Text = SqlValue.Text(value)

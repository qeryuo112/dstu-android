package com.deepstudent.services.sync.data

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.platform.database.SharedDatabase
import com.deepstudent.platform.database.SqlDatabase
import com.deepstudent.platform.database.SqlValue
import com.deepstudent.services.sync.api.SyncChangeReceipt
import com.deepstudent.services.sync.backend.SyncBatch
import com.deepstudent.services.sync.backend.SyncBatchCodec
import com.deepstudent.services.sync.backend.transfer.PendingPublication
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CancellationException

@RunWith(AndroidJUnit4::class)
class S229PublicationAndroidTest {
    @get:Rule val s229Foreground = S229PublicationForegroundRule()

    private val fixture = S229PublicationFixture(InstrumentationRegistry.getInstrumentation().targetContext)

    @After
    fun cleanup() = fixture.close()

    @Test
    fun equalIdsSurvivePartialOwnerCommitAndReopenWithoutChangingThePublication() = runBlocking {
        val remote = S229MemoryProvider()
        var failSecondOwner = false
        remote.afterPut = { failSecondOwner = true }
        val reserved = fixture.open(SharedDatabase.VFS).use { vfs ->
            fixture.open(SharedDatabase.CHAT_V2).use { chat ->
                s229Resource(vfs, "same-id-resource")
                chat.execute("INSERT INTO chat_v2_sessions(id,mode,created_at,updated_at) VALUES('same-id-chat','general_chat','a','a')")
                s229SelectLog(vfs, "resources", "same-id-resource", LOG_ID)
                s229SelectLog(chat, "chat_v2_sessions", "same-id-chat", LOG_ID)
                val journal = fixture.journal(vfs)
                val sources = listOf(
                    fixture.source(vfs, SharedDatabase.VFS),
                    fixture.source(chat, SharedDatabase.CHAT_V2) {
                        if (failSecondOwner) throw IllegalStateException("s229-second-owner")
                    },
                )
                val transfer = fixture.transfer(remote, journal, sources)
                try {
                    transfer.upload(4)
                    fail("second owner should fail after remote publication")
                } catch (error: IllegalStateException) {
                    assertEquals("s229-second-owner", error.message)
                }
                val pending = requireNotNull(journal.pending())
                assertFalse(pending.seed)
                assertEquals(1L, pending.sequence)
                assertEquals(listOf(SyncChangeReceipt("vfs", LOG_ID), SyncChangeReceipt("chat_v2", LOG_ID)), pending.receipts)
                assertEquals(0L, journal.published())
                assertEquals(pending.version, s229Version(vfs, "resources", "same-id-resource"))
                assertEquals(0L, s229Version(chat, "chat_v2_sessions", "same-id-chat"))
                assertArrayEquals(pending.payload, remote.objects[pending.key])
                val batch = SyncBatchCodec.decode(pending.payload)
                assertEquals(1UL, batch.sourceSeq)
                assertEquals(setOf("vfs", "chat_v2"), batch.changes.map { s229Text(it, "database_name") }.toSet())
                assertEquals(listOf(LOG_ID, LOG_ID), batch.changes.map { (it["change_log_id"] as JsonValue.Number).asLongOrNull() })
                pending
            }
        }

        fixture.open(SharedDatabase.VFS).use { vfs ->
            fixture.open(SharedDatabase.CHAT_V2).use { chat ->
                val journal = fixture.journal(vfs)
                assertSameReservation(reserved, requireNotNull(journal.pending()))
                assertEquals(0L, journal.published())
                val sources = listOf(fixture.source(vfs, SharedDatabase.VFS), fixture.source(chat, SharedDatabase.CHAT_V2))
                fixture.transfer(remote, journal, sources).resumePending()
                assertNull(journal.pending())
                assertEquals(reserved.sequence, journal.published())
                assertEquals(reserved.version, s229Version(vfs, "resources", "same-id-resource"))
                assertEquals(reserved.version, s229Version(chat, "chat_v2_sessions", "same-id-chat"))
                assertEquals(setOf(reserved.key), remote.objects.keys)
                assertEquals(1, remote.puts)
                assertArrayEquals(reserved.payload, remote.objects[reserved.key])
            }
        }
        Log.i(TAG, "case=cross_database_equal_ids stage=reopen_and_acknowledge status=ok")
        Unit
    }

    @Test
    fun joinPublishesOnlyNewPendingRowsNotPreviouslyConfirmedHistory() = runBlocking {
        fixture.open(SharedDatabase.VFS).use { vfs ->
            s229ConfirmAll(vfs)
            s229Resource(vfs, "join-old-confirmed")
            s229ConfirmAll(vfs)
            val remote = S229MemoryProvider()
            val journal = fixture.journal(vfs)
            // Normal JOIN composes publication without a RepositorySeed; this is not a pairing UI test.
            val transfer = fixture.transfer(remote, journal, listOf(fixture.source(vfs, SharedDatabase.VFS)))
            transfer.upload(4)
            assertTrue(remote.objects.isEmpty())
            assertNull(journal.pending())
            assertEquals(0L, journal.published())
            assertEquals(6L, s229Version(vfs, "resources", "join-old-confirmed"))

            s229Resource(vfs, "join-new-pending")
            assertEquals(0L, s229Version(vfs, "resources", "join-new-pending"))
            transfer.upload(4)
            val batch = remote.onlyBatch()
            assertEquals(1UL, batch.sourceSeq)
            assertEquals(listOf("join-new-pending"), batch.changes.map { s229Text(it, "record_id") })
            assertEquals("vfs", s229Text(batch.changes.single(), "database_name"))
            assertEquals(6L, s229Version(vfs, "resources", "join-old-confirmed"))
            assertTrue(s229Version(vfs, "resources", "join-new-pending") > 0)
            assertEquals(1L, journal.published())
            assertNull(journal.pending())
        }
        Log.i(TAG, "case=join_confirmed_history stage=incremental_publish status=ok")
        Unit
    }

    @Test
    fun createSeedIncludesEveryEligibleRowAcrossAllFourMigratedDatabases() = runBlocking {
        fixture.withFourOwners { owners ->
            val vfs = owners.getValue(SharedDatabase.VFS)
            val chat = owners.getValue(SharedDatabase.CHAT_V2)
            val mistakes = owners.getValue(SharedDatabase.MISTAKES)
            val usage = owners.getValue(SharedDatabase.LLM_USAGE)
            s229Resource(vfs, "seed-confirmed")
            chat.execute("INSERT INTO chat_v2_sessions(id,mode,created_at,updated_at) VALUES('seed-chat','general_chat','a','a')")
            s229Template(mistakes, "seed-template", builtIn = 0, modified = 0)
            usage.execute(
                "INSERT INTO llm_usage_logs(id,timestamp,provider,model,caller_type) VALUES('seed-usage','2026-01-01T00:00:00Z','p','m','other')",
            )
            owners.values.forEach(::s229ConfirmAll)
            s229Resource(vfs, "seed-pending")
            assertEquals(6L, s229Version(vfs, "resources", "seed-confirmed"))
            assertEquals(0L, s229Version(vfs, "resources", "seed-pending"))

            val remote = S229MemoryProvider()
            val journal = fixture.journal(vfs)
            val transfer = fixture.transfer(remote, journal, fixture.sources(owners), seed = true)
            transfer.upload(4)
            val batch = remote.onlyBatch()
            assertEquals(1UL, batch.sourceSeq)
            assertSeedMatchesRegistryInventory(owners, batch)
            assertContains(batch, "vfs", "resources", "seed-confirmed")
            assertContains(batch, "vfs", "resources", "seed-pending")
            assertContains(batch, "chat_v2", "chat_v2_sessions", "seed-chat")
            assertContains(batch, "mistakes", "custom_anki_templates", "seed-template")
            assertContains(batch, "mistakes", "anki_decks", "deck_default")
            assertContains(batch, "llm_usage", "llm_usage_logs", "seed-usage")
            assertEquals(6L, s229Version(vfs, "resources", "seed-confirmed"))
            assertTrue(s229Version(vfs, "resources", "seed-pending") > 0)
            assertEquals(1L, journal.published())
            assertNull(journal.pending())

            s229Resource(vfs, "after-seed")
            transfer.upload(4)
            val incremental = SyncBatchCodec.decode(remote.objects.values.last())
            assertEquals(2UL, incremental.sourceSeq)
            assertEquals(listOf("after-seed"), incremental.changes.map { s229Text(it, "record_id") })
            assertEquals(2L, journal.published())
        }
        Log.i(TAG, "case=create_seed stage=all_four_inventories_and_incremental status=ok")
        Unit
    }

    @Test
    fun seedExcludesUnmodifiedBuiltInTemplatesButPublishesUserChanges() = runBlocking {
        fixture.withFourOwners { owners ->
            val vfs = owners.getValue(SharedDatabase.VFS)
            val mistakes = owners.getValue(SharedDatabase.MISTAKES)
            s229Template(mistakes, "template-unchanged", builtIn = 1, modified = 0)
            s229Template(mistakes, "template-modified", builtIn = 1, modified = 1)
            s229Template(mistakes, "template-deleted", builtIn = 1, modified = 0, deleted = 1)
            s229Template(mistakes, "template-user", builtIn = 0, modified = 0)
            assertEquals(0L, s229LogCount(mistakes, "custom_anki_templates", "template-unchanged"))
            assertTrue(s229LogCount(mistakes, "custom_anki_templates", "template-modified") > 0)
            val remote = S229MemoryProvider()
            val journal = fixture.journal(vfs)
            val transfer = fixture.transfer(remote, journal, fixture.sources(owners), seed = true)
            transfer.upload(4)
            val templates = remote.onlyBatch().changes
                .filter { s229Text(it, "database_name") == "mistakes" && s229Text(it, "table_name") == "custom_anki_templates" }
                .map { s229Text(it, "record_id") }.toSet()
            assertFalse("template-unchanged" in templates)
            assertTrue(templates.containsAll(setOf("template-modified", "template-deleted", "template-user")))
            assertEquals(0L, s229LogCount(mistakes, "custom_anki_templates", "template-unchanged"))

            mistakes.execute(
                "UPDATE custom_anki_templates SET user_modified=1 WHERE id=?",
                listOf(SqlValue.Text("template-unchanged")),
            )
            transfer.upload(4)
            val incremental = SyncBatchCodec.decode(remote.objects.values.last())
            assertEquals(2UL, incremental.sourceSeq)
            assertEquals(listOf("template-unchanged"), incremental.changes.map { s229Text(it, "record_id") })
            assertEquals("mistakes", s229Text(incremental.changes.single(), "database_name"))
            assertEquals(2L, journal.published())
            assertNull(journal.pending())
        }
        Log.i(TAG, "case=bundled_templates stage=seed_filter_and_later_edit status=ok")
        Unit
    }

    @Test
    fun cancellationAfterPutRetainsReservationAndDoesNotAcknowledgeLaterChanges() = runBlocking {
        val remote = S229MemoryProvider().apply { cancelAfterPut = true }
        val reserved = fixture.open(SharedDatabase.VFS).use { vfs ->
            s229ConfirmAll(vfs)
            s229Resource(vfs, "cancel-reserved")
            val journal = fixture.journal(vfs)
            val transfer = fixture.transfer(remote, journal, listOf(fixture.source(vfs, SharedDatabase.VFS)))
            val task = async(start = CoroutineStart.UNDISPATCHED) { transfer.upload(4) }
            try {
                withTimeout(8_000) { task.await() }
                fail("publication coroutine should be cancelled after PUT")
            } catch (timeout: TimeoutCancellationException) {
                task.cancel()
                throw AssertionError("publication cancellation did not complete", timeout)
            } catch (_: CancellationException) {
                // The object exists, but the readback/acknowledgement boundary was cancelled.
            }
            val pending = requireNotNull(journal.pending())
            assertEquals(1L, pending.sequence)
            assertEquals(0L, journal.published())
            assertEquals(0L, s229Version(vfs, "resources", "cancel-reserved"))
            assertArrayEquals(pending.payload, remote.objects[pending.key])
            s229Resource(vfs, "cancel-later")
            assertEquals(0L, s229Version(vfs, "resources", "cancel-later"))
            pending
        }
        remote.cancelAfterPut = false
        fixture.open(SharedDatabase.VFS).use { vfs ->
            val journal = fixture.journal(vfs)
            assertSameReservation(reserved, requireNotNull(journal.pending()))
            val transfer = fixture.transfer(remote, journal, listOf(fixture.source(vfs, SharedDatabase.VFS)))
            transfer.resumePending()
            assertEquals(1L, journal.published())
            assertNull(journal.pending())
            assertEquals(reserved.version, s229Version(vfs, "resources", "cancel-reserved"))
            assertEquals(0L, s229Version(vfs, "resources", "cancel-later"))
            assertEquals(1, remote.puts)
            assertArrayEquals(reserved.payload, remote.objects[reserved.key])

            transfer.upload(4)
            val second = SyncBatchCodec.decode(remote.objects.values.last())
            assertEquals(2UL, second.sourceSeq)
            assertEquals(listOf("cancel-later"), second.changes.map { s229Text(it, "record_id") })
            assertTrue(s229Version(vfs, "resources", "cancel-later") > 0)
            assertEquals(2L, journal.published())
            assertEquals(2, remote.puts)
        }
        Log.i(TAG, "case=cancel_after_put stage=preserve_reservation_and_later_change status=ok")
        Unit
    }

    private fun assertSameReservation(expected: PendingPublication, actual: PendingPublication) {
        assertEquals(expected.key, actual.key)
        assertEquals(expected.sequence, actual.sequence)
        assertEquals(expected.version, actual.version)
        assertEquals(expected.receipts, actual.receipts)
        assertEquals(expected.seed, actual.seed)
        assertArrayEquals(expected.payload, actual.payload)
    }

    private fun assertContains(batch: SyncBatch, database: String, table: String, record: String) {
        assertTrue(
            "missing expected seed row from $database.$table",
            batch.changes.any {
                s229Text(it, "database_name") == database && s229Text(it, "table_name") == table && s229Text(it, "record_id") == record
            },
        )
    }

    private fun assertSeedMatchesRegistryInventory(owners: Map<SharedDatabase, SqlDatabase>, batch: SyncBatch) {
        val groups = batch.changes.groupBy { s229Text(it, "database_name") to s229Text(it, "table_name") }
        val nonempty = mutableSetOf<Pair<String, String>>()
        owners.forEach { (owner, db) ->
            fixture.registry.rowSyncKeys(owner).keys.forEach { table ->
                val quoted = "\"" + table.replace("\"", "\"\"") + "\""
                val filter = if (table == "custom_anki_templates") " WHERE is_built_in=0 OR user_modified=1 OR user_deleted=1" else ""
                val count = db.query("SELECT COUNT(*) FROM $quoted$filter") { it.long(0) }.single()
                val key = owner.id to table
                val rows = groups[key].orEmpty()
                assertEquals("seed count mismatch for ${owner.id}.$table", count, rows.size.toLong())
                assertEquals("duplicate seed keys for ${owner.id}.$table", rows.size, rows.map { s229Text(it, "record_id") }.toSet().size)
                if (count > 0) nonempty += key
            }
        }
        assertEquals(nonempty, groups.keys)
        assertNotNull(groups["mistakes" to "anki_decks"])
    }

    private companion object {
        const val LOG_ID = 41L
        const val TAG = "S229PublicationTest"
    }
}

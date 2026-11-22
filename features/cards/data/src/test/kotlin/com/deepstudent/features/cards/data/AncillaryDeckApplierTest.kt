package com.deepstudent.features.cards.data

import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.core.common.logging.LogRecord
import com.deepstudent.core.common.logging.Logger
import com.deepstudent.platform.database.DatabaseFactory
import com.deepstudent.platform.database.MigrationSource
import com.deepstudent.platform.database.SharedDatabase
import com.deepstudent.platform.database.SqlDatabase
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CancellationException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class AncillaryDeckApplierTest {
    private val directory = Files.createTempDirectory("incoming-deck").toFile()
    private val repositoryRoot = generateSequence(File(System.getProperty("user.dir") ?: error("missing working directory"))) { it.parentFile }
        .first { File(it, "config/schema/final-lock.json").isFile }
    private val source = MigrationSource { repositoryRoot.resolve(it).readBytes() }

    @After fun cleanup() { directory.deleteRecursively() }

    private fun database(): SqlDatabase = DatabaseFactory.openShared(directory.resolve("mistakes.db"), SharedDatabase.MISTAKES, source)

    @Test fun migratedDesktopSeedReplayDoesNotEchoOrMarkExistingPending() {
        val file = directory.resolve("mistakes.db")
        database().use { db ->
            assertEquals(SharedDatabase.MISTAKES, db.migrationReport!!.database)
            assertEquals(1, scalar(db, "SELECT count(*) FROM anki_decks WHERE id='deck_default' AND name='Default'"))
            db.execute("UPDATE anki_decks SET description='local pending',updated_at='2026-11-15T00:00:00Z' WHERE id='deck_default'")
            val pending = pendingIds(db)
            val incoming = change("deck_default", "Default", "2026-11-16T00:00:00Z", extra = ",\"description\":\"desktop\"")
            AncillaryDeckApplier(db).apply(listOf(incoming))
            assertEquals("desktop", queryText(db, "SELECT description FROM anki_decks WHERE id='deck_default'"))
            assertEquals(pending, pendingIds(db))
            val count = scalar(db, "SELECT count(*) FROM __change_log")
            assertEquals(1, scalar(db, "SELECT count(*) FROM __change_log WHERE id>${pending.max()} AND sync_version=1"))
            AncillaryDeckApplier(db).apply(listOf(incoming))
            assertEquals(count, scalar(db, "SELECT count(*) FROM __change_log"))
            assertEquals(pending, pendingIds(db))
        }
        DatabaseFactory.openShared(file, SharedDatabase.MISTAKES, source).use { db ->
            assertEquals("desktop", queryText(db, "SELECT description FROM anki_decks WHERE id='deck_default'"))
        }
    }

    @Test fun exactMigratedDesktopSnapshotRowIsANoopIncludingExistingPending() {
        DatabaseFactory.openShared(directory.resolve("desktop.db"), SharedDatabase.MISTAKES, source).use { desktop ->
            database().use { target ->
                val snapshot = desktop.query("SELECT id,name,description,config_json,created_at,updated_at,deleted_at,device_id,local_version FROM anki_decks WHERE id='deck_default'") { row ->
                    val fields = deckColumns.mapIndexed { index, name ->
                        name to when {
                            row.isNull(index) -> JsonValue.Null
                            name == "local_version" -> JsonValue.integer(row.long(index))
                            else -> JsonValue.text(row.text(index))
                        }
                    }.toMap()
                    val change = JsonValue.Object.Builder().put("table_name", "anki_decks").put("record_id", row.text(0))
                        .put("operation", "Insert").put("data", JsonValue.Object.from(fields))
                        .put("changed_at", "2026-11-16T00:00:00Z").put("database_name", "mistakes")
                        .put("source_device_id", "s1-25-desktop").put("source_seq", 1).build()
                    change.literal().toByteArray()
                }.single()
                val before = pendingIds(target)
                val applier = AncillaryDeckApplier(target)
                applier.apply(listOf(snapshot))
                applier.apply(listOf(snapshot))
                assertEquals(before, pendingIds(target))
                assertEquals(before.size.toLong(), scalar(target, "SELECT count(*) FROM __change_log"))
            }
        }
    }

    @Test fun nameBusinessKeyAliasIsPersistentAndDoesNotOverwriteDifferentRows() {
        database().use { db ->
            db.execute("INSERT INTO anki_decks(id,name,created_at,updated_at,description) VALUES('local','Deck','2023-01-01T00:00:00Z','2024-01-01T00:00:00Z','prior')")
            db.execute("INSERT INTO anki_decks(id,name,created_at,updated_at,description) VALUES('other','Other','2023-01-01T00:00:00Z','2024-01-01T00:00:00Z','untouched')")
            val applier = AncillaryDeckApplier(db)
            applier.apply(listOf(change("remote", "Deck", "2025-01-01T00:00:00Z", extra = ",\"description\":\"winner\",\"future\":9")))
            assertEquals("winner", queryText(db, "SELECT description FROM anki_decks WHERE id='local'"))
            assertEquals("untouched", queryText(db, "SELECT description FROM anki_decks WHERE id='other'"))
            assertEquals("local", queryText(db, "SELECT canonical_id FROM __sync_id_aliases WHERE table_name='anki_decks' AND remote_id='remote'"))
            assertEquals(9L, (unknown(db, "local")["future"] as JsonValue.Number).asLongOrNull())
            applier.apply(listOf(change("remote", "Renamed", "2025-02-01T00:00:00Z")))
            assertEquals("Renamed", queryText(db, "SELECT name FROM anki_decks WHERE id='local'"))
            val conflict = change("other", "Renamed", "2025-03-01T00:00:00Z")
            assertThrows(IllegalStateException::class.java) { applier.apply(listOf(conflict)) }
            assertEquals("untouched", queryText(db, "SELECT description FROM anki_decks WHERE id='other'"))
        }
    }

    @Test fun versionedFutureFieldsPreserveNullAndFullIntegerWithoutRegression() {
        database().use { db ->
            val applier = AncillaryDeckApplier(db)
            val first = ",\"future\":9007199254740993,\"nothing\":null,\"__sync_field_deltas\":{\"future\":{\"hlc\":\"001700000000000-00003\",\"dev\":\"remote\",\"seq\":18446744073709551615}}"
            applier.apply(listOf(change("opaque", "Opaque", "2024-01-01T00:00:00Z", extra = first)))
            var fields = unknown(db, "opaque")
            assertEquals(JsonValue.Null, fields["nothing"])
            val envelope = fields["future"] as JsonValue.Object
            assertEquals("9007199254740993", (envelope["value"] as JsonValue.Number).literal)
            assertEquals("18446744073709551615", (envelope["seq"] as JsonValue.Number).literal)
            assertEquals("001700000000000-00003", (envelope["hlc"] as JsonValue.Text).value)
            val older = ",\"future\":1,\"__sync_field_deltas\":{\"future\":{\"hlc\":\"001700000000000-00002\",\"dev\":\"remote\"}}"
            applier.apply(listOf(change("opaque", "Opaque", "2024-01-02T00:00:00Z", extra = older)))
            fields = unknown(db, "opaque")
            assertEquals(envelope, fields["future"])
            applier.apply(listOf(change("opaque", "Opaque", "2024-01-03T00:00:00Z", extra = ",\"future\":2")))
            assertEquals(envelope, unknown(db, "opaque")["future"])
            val orphan = ",\"__sync_field_deltas\":{\"delayed\":{\"hlc\":\"001700000000000-00004\",\"dev\":\"remote\"}}"
            applier.apply(listOf(change("opaque", "Opaque", "2024-01-04T00:00:00Z", extra = orphan)))
            assertEquals("001700000000000-00004", (((unknown(db, "opaque")["__sync_field_deltas"] as JsonValue.Object)["delayed"] as JsonValue.Object)["hlc"] as JsonValue.Text).value)
            applier.apply(listOf(change("opaque", "Opaque", "2024-01-05T00:00:00Z", extra = ",\"delayed\":null")))
            val delayed = unknown(db, "opaque")["delayed"] as JsonValue.Object
            assertEquals(JsonValue.Null, delayed["value"])
            assertEquals("001700000000000-00004", (delayed["hlc"] as JsonValue.Text).value)
        }
    }

    @Test fun newerDeleteBlocksStaleReplayAndAllowsRestore() {
        database().use { db ->
            val applier = AncillaryDeckApplier(db)
            val original = change("one", "One", "2024-01-01T00:00:00Z")
            val delete = delete("one", "2024-02-01T00:00:00Z")
            val restore = change("one", "One", "2024-03-01T00:00:00Z")
            applier.apply(listOf(original, delete))
            assertEquals("2024-02-01T00:00:00Z", queryText(db, "SELECT deleted_at FROM anki_decks WHERE id='one'"))
            val count = scalar(db, "SELECT count(*) FROM __change_log")
            applier.apply(listOf(original, delete))
            assertEquals(count, scalar(db, "SELECT count(*) FROM __change_log"))
            applier.apply(listOf(restore))
            assertEquals(1, scalar(db, "SELECT count(*) FROM anki_decks WHERE id='one' AND deleted_at IS NULL"))
            applier.apply(listOf(delete))
            assertEquals(1, scalar(db, "SELECT count(*) FROM anki_decks WHERE id='one' AND deleted_at IS NULL"))
        }
    }

    @Test fun rejectedBatchRollsBackRowsAliasesVaultAndNewLogs() {
        database().use { db ->
            val applier = AncillaryDeckApplier(db)
            val before = pendingIds(db)
            val invalid = change("wrong", "Bad", "2024-01-01T00:00:00Z").decodeToString().replace("anki_decks", "anki_cards").toByteArray()
            val error = assertThrows(IllegalStateException::class.java) {
                applier.apply(listOf(change("ok", "OK", "2024-01-01T00:00:00Z", extra = ",\"future\":2"), invalid))
            }
            assertEquals("Incoming deck sync failed", error.message)
            assertNull(error.cause)
            assertEquals(0, scalar(db, "SELECT count(*) FROM anki_decks WHERE id='ok'"))
            assertEquals(before, pendingIds(db))
            val mismatched = change("x", "X", "2024-01-01T00:00:00Z").decodeToString().replace("\"id\":\"x\"", "\"id\":\"other\"").toByteArray()
            assertThrows(IllegalStateException::class.java) { applier.apply(listOf(mismatched)) }
            val badType = change("x", "X", "2024-01-01T00:00:00Z", extra = ",\"description\":{\"unsafe\":1}")
            assertThrows(IllegalStateException::class.java) { applier.apply(listOf(badType)) }
            assertThrows(IllegalStateException::class.java) {
                applier.apply(listOf(change("ok", "OK", "2024-01-01T00:00:00Z"), change("bad", "Bad", "2024-01-01T00:00:00Z", extra = ",\"local_version\":9223372036854775808")))
            }
            assertEquals(0, scalar(db, "SELECT count(*) FROM anki_decks WHERE id='ok'"))
            db.execute("INSERT INTO anki_decks(id,name,created_at,updated_at) VALUES('canonical','Alias','2023-01-01T00:00:00Z','2023-01-01T00:00:00Z')")
            db.transaction { deckSetup() }
            val incomplete = change("broken", "Broken", "2024-01-01T00:00:00Z").decodeToString()
                .replace("\"created_at\":\"2023-01-01T00:00:00Z\",", "").toByteArray()
            assertThrows(IllegalStateException::class.java) {
                applier.apply(listOf(change("remote", "Alias", "2024-01-01T00:00:00Z", extra = ",\"future\":2"), incomplete))
            }
            assertEquals("2023-01-01T00:00:00Z", queryText(db, "SELECT updated_at FROM anki_decks WHERE id='canonical'"))
            assertEquals(0, scalar(db, "SELECT count(*) FROM __sync_id_aliases WHERE remote_id='remote'"))
            assertEquals(0, scalar(db, "SELECT count(*) FROM __sync_unknown_fields WHERE record_id='canonical'"))
        }
    }

    @Test fun equalTimeOrdersByDeviceThenContentAndOlderCannotWin() {
        database().use { db ->
            val applier = AncillaryDeckApplier(db)
            applier.apply(listOf(change("lww", "LWW", "2024-01-01T00:00:00Z", "b", ",\"description\":\"one\",\"device_id\":\"b\"")))
            applier.apply(listOf(change("lww", "LWW", "2024-01-01T00:00:00Z", "a", ",\"description\":\"bad\",\"device_id\":\"a\"")))
            assertEquals("one", queryText(db, "SELECT description FROM anki_decks WHERE id='lww'"))
            applier.apply(listOf(change("lww", "LWW", "2024-01-01T00:00:00Z", "z", ",\"description\":\"two\",\"device_id\":\"z\"")))
            assertEquals("two", queryText(db, "SELECT description FROM anki_decks WHERE id='lww'"))
            applier.apply(listOf(change("lww", "LWW", "2023-01-01T00:00:00Z", "z", ",\"description\":\"old\"")))
            assertEquals("two", queryText(db, "SELECT description FROM anki_decks WHERE id='lww'"))
            val local = db.deckRow("lww")!!
            val winner = JsonValue.Object.from(local.asMap() + ("description" to JsonValue.text("zzz")))
            val loser = JsonValue.Object.from(local.asMap() + ("description" to JsonValue.text("aaa")))
            applier.apply(listOf(changeWithData(winner)))
            assertEquals("zzz", queryText(db, "SELECT description FROM anki_decks WHERE id='lww'"))
            applier.apply(listOf(changeWithData(loser)))
            assertEquals("zzz", queryText(db, "SELECT description FROM anki_decks WHERE id='lww'"))
        }
    }

    private fun changeWithData(row: JsonValue.Object): ByteArray = JsonValue.Object.Builder()
        .put("table_name", "anki_decks").put("record_id", "lww").put("operation", "Update")
        .put("database_name", "mistakes").put("changed_at", "2024-01-01T00:00:00Z")
        .put("source_device_id", "z").put("data", row).build().literal().toByteArray()

    @Test fun cancellationAfterFirstRowRollsBackAndPreservesLocalPending() {
        database().use { db ->
            db.execute("UPDATE anki_decks SET description='local' WHERE id='deck_default'")
            val pending = pendingIds(db)
            val count = scalar(db, "SELECT count(*) FROM __change_log")
            var checks = 0
            var sawFirst = false
            val applier = AncillaryDeckApplier(db, checkCancelled = {
                checks++
                if (checks == 5) {
                    sawFirst = scalar(db, "SELECT count(*) FROM anki_decks WHERE id='first'") == 1L
                    throw CancellationException("cancel")
                }
            })
            assertThrows(CancellationException::class.java) {
                applier.apply(listOf(change("first", "First", "2024-01-01T00:00:00Z"), change("second", "Second", "2024-01-01T00:00:00Z")))
            }
            assertEquals(true, sawFirst)
            assertEquals(0, scalar(db, "SELECT count(*) FROM anki_decks WHERE id IN ('first','second')"))
            assertEquals(count, scalar(db, "SELECT count(*) FROM __change_log"))
            assertEquals(pending, pendingIds(db))
            assertEquals("local", queryText(db, "SELECT description FROM anki_decks WHERE id='deck_default'"))
        }
    }

    @Test fun cancellationImmediatelyBeforeCommitRollsBackEchoMarking() {
        database().use { db ->
            val pending = pendingIds(db)
            val before = scalar(db, "SELECT count(*) FROM __change_log")
            var checks = 0
            var sawEchoMarked = false
            val applier = AncillaryDeckApplier(db, checkCancelled = {
                checks++
                if (checks == 5) {
                    sawEchoMarked = scalar(db, "SELECT count(*) FROM __change_log WHERE sync_version=1") == 1L
                    throw CancellationException("cancel")
                }
            })
            assertThrows(CancellationException::class.java) {
                applier.apply(listOf(change("first", "First", "2024-01-01T00:00:00Z")))
            }
            assertEquals(true, sawEchoMarked)
            assertEquals(0, scalar(db, "SELECT count(*) FROM anki_decks WHERE id='first'"))
            assertEquals(before, scalar(db, "SELECT count(*) FROM __change_log"))
            assertEquals(pending, pendingIds(db))
        }
    }

    @Test fun unexpectedCallbackFailureIsSanitized() {
        database().use { db ->
            val secret = "private_callback_content"
            val records = mutableListOf<LogRecord>()
            val logger = object : Logger { override fun log(record: LogRecord) { records += record } }
            val applier = AncillaryDeckApplier(db, logger) { throw IllegalArgumentException(secret) }
            val error = assertThrows(IllegalStateException::class.java) { applier.apply(emptyList()) }
            assertEquals("Incoming deck sync failed", error.message)
            assertNull(error.cause)
            assertFalse(records.toString().contains(secret))
        }
    }

    @Test fun loggingAndFailuresDoNotLeakContentAndCancellationPropagates() {
        database().use { db ->
            val records = mutableListOf<LogRecord>()
            val applier = AncillaryDeckApplier(db, object : Logger { override fun log(record: LogRecord) { records += record } })
            val secret = "private_secret_890"
            applier.apply(listOf(change("log", secret, "2024-01-01T00:00:00Z")))
            assertThrows(IllegalStateException::class.java) { applier.apply(listOf(secret.toByteArray())) }
            assertFalse(records.toString().contains(secret))
            assertEquals(listOf("complete", "failed"), records.map { (it.fields["status"] as JsonValue.Text).value })
            try {
                Thread.currentThread().interrupt()
                assertThrows(CancellationException::class.java) { applier.apply(listOf(change("cancel", "Cancel", "2024-01-01T00:00:00Z"))) }
            } finally { Thread.interrupted() }
            assertEquals(0, scalar(db, "SELECT count(*) FROM anki_decks WHERE id='cancel'"))
        }
    }

    private fun pendingIds(db: SqlDatabase): List<Long> = db.query("SELECT id FROM __change_log WHERE COALESCE(sync_version,0)=0 ORDER BY id") { it.long(0) }
    private fun scalar(db: SqlDatabase, sql: String): Long = db.query(sql) { it.long(0) }.single()
    private fun queryText(db: SqlDatabase, sql: String): String = db.query(sql) { it.text(0) }.single()
    private fun unknown(db: SqlDatabase, id: String): JsonValue.Object = JsonValue.parseObject(db.query("SELECT fields_json FROM __sync_unknown_fields WHERE table_name='anki_decks' AND record_id=?", listOf(text(id))) { it.text(0) }.single())

    private fun change(id: String, name: String, time: String, device: String = "peer", extra: String = ""): ByteArray =
        """{"table_name":"anki_decks","record_id":"$id","operation":"Insert","database_name":"mistakes","changed_at":"$time","source_device_id":"$device","source_seq":1,"data":{"id":"$id","name":"$name","created_at":"2023-01-01T00:00:00Z","updated_at":"$time","deleted_at":null$extra}}""".toByteArray()

    private fun delete(id: String, time: String): ByteArray =
        """{"table_name":"anki_decks","record_id":"$id","operation":"Delete","database_name":"mistakes","changed_at":"$time","source_device_id":"peer","source_seq":1,"data":null}""".toByteArray()
}

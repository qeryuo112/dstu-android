package com.deepstudent.app.storage

import com.deepstudent.core.common.id.Digests
import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.core.common.logging.LogRecord
import com.deepstudent.core.common.logging.Logger
import com.deepstudent.core.common.logging.NoopLogger
import com.deepstudent.platform.database.MigrationReport
import com.deepstudent.platform.database.SqlDatabase
import com.deepstudent.platform.database.SqlOperations
import com.deepstudent.platform.database.SqlReadOperations
import com.deepstudent.platform.database.SqlRow
import com.deepstudent.platform.database.SqlValue
import com.deepstudent.services.resources.api.AssetDigest
import com.deepstudent.services.resources.api.AssetKey
import com.deepstudent.services.resources.api.assets.AssetReferenceOwner
import com.deepstudent.services.resources.api.assets.AssetTarget
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class AssetLedgerCompositionTest {
    private val root = Files.createTempDirectory("s208-app-unit-").toFile()
    private val files = root.resolve("files").apply { mkdirs(); resolve("active").mkdirs() }
    private val cache = root.resolve("cache").apply { mkdirs() }
    private val bytes = "fixture".toByteArray()
    private val target = AssetTarget(AssetKey.parse("active/chat_attachments/a/file"),
        AssetDigest(Digests.sha256Hex(bytes), bytes.size.toLong()))

    @After fun cleanup() { root.deleteRecursively() }

    @Test fun chatIsLazyAndOwnedButVfsIsBorrowed() {
        val vfs = FakeDatabase()
        val chat = FakeDatabase()
        var opens = 0
        val owner = MainAssetLedgerResources.create(files.resolve("active"), cache, vfs, { opens++; chat }, NoopLogger)
        val ledger = owner.ledger
        assertEquals(0, opens)
        assertTrue(ledger.scan().manifest.entries.isEmpty())
        assertTrue(ledger.deletionSnapshot().ready.isEmpty())
        ledger.cleanupOwnScratch()
        assertEquals(0, opens)
        val report = ledger.references(target)
        assertEquals(0L, report.counts.single { it.owner == AssetReferenceOwner.CHAT_ATTACHMENTS }.active)
        ledger.references(target)
        assertEquals(1, opens)
        owner.close()
        owner.close()
        assertEquals(1, chat.closes)
        assertEquals(0, vfs.closes)
        assertThrows(IllegalStateException::class.java) { ledger.scan() }
        assertThrows(IllegalStateException::class.java) { ledger.references(target) }
        assertEquals(1, opens)
    }

    @Test fun closingUnopenedOwnerDoesNotOpenOrCloseDatabase() {
        val vfs = FakeDatabase()
        var opens = 0
        val owner = MainAssetLedgerResources.create(files.resolve("active"), cache, vfs, { opens++; FakeDatabase() }, NoopLogger)
        owner.close()
        owner.close()
        assertEquals(0, opens)
        assertEquals(0, vfs.closes)
        assertThrows(IllegalStateException::class.java) { owner.ledger.deletionSnapshot() }
    }

    @Test fun failedChatOpenIsNotCachedAndDoesNotBecomeAZeroReferenceReport() {
        val records = mutableListOf<LogRecord>()
        val logger = object : Logger { override fun log(record: LogRecord) { records += record } }
        val chat = FakeDatabase()
        var opens = 0
        val owner = MainAssetLedgerResources.create(files.resolve("active"), cache, FakeDatabase(), {
            if (++opens == 1) throw IllegalStateException("fixture-only secret")
            chat
        }, logger)
        try {
            assertThrows(IllegalStateException::class.java) { owner.ledger.references(target) }
            assertEquals("failed", (records.single().fields["state"] as JsonValue.Text).value)
            assertFalse(records.single().toString().contains("fixture-only secret"))
            assertEquals(3, owner.ledger.references(target).counts.size)
            assertEquals(2, opens)
        } finally { owner.close() }
        assertEquals(1, chat.closes)
    }

    @Test fun closeWaitsForInFlightQueryAndThenRejectsFurtherUse() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closeStarted = CountDownLatch(1)
        val chat = FakeDatabase {
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS))
        }
        val owner = MainAssetLedgerResources.create(files.resolve("active"), cache, FakeDatabase(), { chat }, NoopLogger)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val query = executor.submit<Any> { owner.ledger.references(target) }
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            val closing = executor.submit { closeStarted.countDown(); owner.close() }
            assertTrue(closeStarted.await(10, TimeUnit.SECONDS))
            Thread.sleep(100)
            assertFalse(closing.isDone)
            assertEquals(0, chat.closes)
            release.countDown()
            query.get(10, TimeUnit.SECONDS)
            closing.get(10, TimeUnit.SECONDS)
            assertEquals(1, chat.closes)
            assertThrows(IllegalStateException::class.java) { owner.ledger.references(target) }
        } finally {
            release.countDown()
            executor.shutdownNow()
            owner.close()
        }
    }

    private class FakeDatabase(private val beforeSnapshot: () -> Unit = {}) : SqlDatabase {
        var closes = 0
        override val migrationReport: MigrationReport? = null
        override fun execute(sql: String, args: List<SqlValue>) = error("unexpected write")
        override fun <T> transaction(block: SqlOperations.() -> T): T = error("unexpected transaction")
        override fun <T> readSnapshot(block: SqlReadOperations.() -> T): T {
            check(closes == 0)
            beforeSnapshot()
            return block(this)
        }
        override fun <T> query(sql: String, args: List<SqlValue>, map: (SqlRow) -> T): List<T> =
            if ("COALESCE" in sql) listOf(map(ZeroRow)) else emptyList()
        override fun close() { closes++ }
    }

    private object ZeroRow : SqlRow {
        override fun isNull(index: Int) = false
        override fun long(index: Int) = 0L
        override fun double(index: Int) = 0.0
        override fun text(index: Int) = ""
        override fun blob(index: Int) = byteArrayOf()
    }
}

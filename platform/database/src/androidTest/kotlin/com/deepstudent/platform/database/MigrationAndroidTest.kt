package com.deepstudent.platform.database

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MigrationAndroidTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = File(context.cacheDir, "s1-05-migrations-${System.nanoTime()}").apply { mkdirs() }
    private val source = BundledMigrationSource(context)

    @After fun cleanup() { root.deleteRecursively() }

    @Test fun allSharedDatabasesMatchDesktopAndRestartWithoutMigrations() {
        val expected = mapOf(
            SharedDatabase.VFS to (76 to "7dec0d2ff3cf1c40cb15e73396c05cbcab925487661b3e09a69a6b1f30f8345b"),
            SharedDatabase.CHAT_V2 to (30 to "2a29bf29771c7381db4cac8cdbad325cddad8d86bc06795a369176e4654e8b7a"),
            SharedDatabase.MISTAKES to (24 to "fdd31bdf68ccfa505b88e946a24eb4ae66dc43e13ccf3102f045a67af36b4e31"),
            SharedDatabase.LLM_USAGE to (8 to "ed69ae106103c3b48460d898be47fdfe12124e26f392e4636d1fe7816beb6245"),
        )
        expected.forEach { (database, golden) ->
            val file = root.resolve("${database.id}.db")
            DatabaseFactory.openShared(file, database, source).use { db ->
                assertEquals(golden.first, db.migrationReport?.appliedCount)
                assertEquals(golden.second, db.migrationReport?.fingerprint)
                assertEquals(listOf("ok"), db.query("PRAGMA integrity_check") { it.text(0) })
                assertTrue(db.query("PRAGMA foreign_key_check") { it.long(0) }.isEmpty())
            }
            DatabaseFactory.openShared(file, database, source).use { db -> assertEquals(0, db.migrationReport?.appliedCount) }
        }
    }

    @Test fun assetByteDriftIsLocatedAndDoesNotCreateDatabase() {
        val target = "config/schema/history/chat_v2/V20260130__init.sql"
        val changed = MigrationSource { path -> source.read(path).let { if (path == target) it + 10 else it } }
        val file = root.resolve("never-created.db")
        val error = assertThrows(MigrationException::class.java) {
            DatabaseFactory.openShared(file, SharedDatabase.CHAT_V2, changed)
        }
        assertEquals(MigrationDiagnostic.SQL_BYTES_DRIFT, error.diagnostic)
        assertEquals(20260130, error.version)
        assertFalse(file.exists())
    }

    @Test fun localDatabaseUsesBundledDriverAndTransactions() {
        val file = root.resolve("runtime.db")
        DatabaseFactory.openLocal(file).use { db ->
            assertEquals(listOf("3.50.1"), db.query("SELECT sqlite_version()") { it.text(0) })
            db.execute("CREATE TABLE t(value TEXT, n INTEGER)")
            db.transaction { execute("INSERT INTO t VALUES(?,?)", listOf(SqlValue.Text("中文"), SqlValue.Integer(Long.MAX_VALUE))) }
            assertThrows(IllegalArgumentException::class.java) {
                db.transaction { execute("INSERT INTO t VALUES('rolled back',1)"); throw IllegalArgumentException() }
            }
        }
        DatabaseFactory.openLocal(file).use { db ->
            assertEquals(listOf("中文:${Long.MAX_VALUE}"), db.query("SELECT value,n FROM t") { "${it.text(0)}:${it.long(1)}" })
        }
    }
}

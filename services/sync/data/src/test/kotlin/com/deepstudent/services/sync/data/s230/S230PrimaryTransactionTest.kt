package com.deepstudent.services.sync.data.s230

import com.deepstudent.platform.database.SharedDatabase
import com.deepstudent.platform.database.SqlValue
import com.deepstudent.services.sync.data.apply.IncomingDomain
import com.deepstudent.services.sync.data.apply.IncomingTransactionDatabase
import com.deepstudent.services.sync.data.apply.PrimaryIncomingAdapter
import com.deepstudent.services.sync.data.apply.RegisteredIncomingRows
import com.deepstudent.services.sync.data.apply.RegisteredSqlRowApplier
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.file.Files

class S230PrimaryTransactionTest {
    private val root = Files.createTempDirectory("s230-primary-").toFile()

    @After fun cleanup() {
        root.deleteRecursively()
    }

    @Test fun companionEchoIsSuppressedWithoutMarkingOldLocalPending() {
        S230MigratedFixture(root, s230HostMigrationSource()).use { fixture ->
            val db = fixture.db(SharedDatabase.VFS)
            db.execute("INSERT INTO todo_lists(id,title,created_at,updated_at) VALUES('local','Local','2026-01-01','2026-01-01')")
            val old = db.query("SELECT id FROM __change_log WHERE table_name='todo_lists' AND record_id='local'") { it.long(0) }.single()
            val view = IncomingTransactionDatabase(db)
            val primary = object : com.deepstudent.services.sync.api.DomainSyncAdapter by fixture.primary {
                override fun apply(device: String, seq: Long, changes: List<ByteArray>) {
                    view.transaction {
                        execute(
                            "INSERT INTO __android_s230_test_cursor(device,seq) VALUES(?,?) " +
                                "ON CONFLICT(device) DO UPDATE SET seq=excluded.seq",
                            listOf(SqlValue.Text(device), SqlValue.Integer(seq)),
                        )
                    }
                }
            }
            val registration = RegisteredIncomingRows(
                SharedDatabase.VFS,
                fixture.registry,
                listOf(IncomingDomain(SharedDatabase.VFS, "todo", listOf("todo_lists"))),
            )
            val owner = PrimaryIncomingAdapter(primary, view, setOf("folders"), RegisteredSqlRowApplier(registration))
            val data = """{"id":"remote","title":"Remote","created_at":"2026-01-01","updated_at":"2026-01-01"}"""
            val remote = s230Change("vfs", "todo_lists", "remote", data)
            owner.apply("peer", 1, listOf(remote))
            val statement = "SELECT COALESCE(sync_version,0) FROM __change_log WHERE id=?"
            val pending = db.query(statement, listOf(SqlValue.Integer(old))) { it.long(0) }
            assertEquals(0L, pending.single())
            assertEquals(1L, db.query("SELECT COALESCE(sync_version,0) FROM __change_log WHERE record_id='remote'") { it.long(0) }.single())
            assertEquals(1L, fixture.primary.cursor("peer"))
        }
    }

    @Test fun companionFailureRollsBackPrimaryCursorAndRowsTogether() {
        S230MigratedFixture(root, s230HostMigrationSource()).use { fixture ->
            val db = fixture.db(SharedDatabase.VFS)
            val view = IncomingTransactionDatabase(db)
            val primary = object : com.deepstudent.services.sync.api.DomainSyncAdapter by fixture.primary {
                override fun apply(device: String, seq: Long, changes: List<ByteArray>) {
                    view.transaction {
                        execute(
                            "INSERT INTO __android_s230_test_cursor(device,seq) VALUES(?,?)",
                            listOf(SqlValue.Text(device), SqlValue.Integer(seq)),
                        )
                    }
                }
            }
            val registration = RegisteredIncomingRows(
                SharedDatabase.VFS,
                fixture.registry,
                listOf(IncomingDomain(SharedDatabase.VFS, "todo", listOf("todo_items"))),
            )
            val owner = PrimaryIncomingAdapter(primary, view, setOf("folders"), RegisteredSqlRowApplier(registration))
            val data = """{"id":"missing-parent","todo_list_id":"absent","title":"x",""" +
                """"created_at":"2026-01-01","updated_at":"2026-01-01"}"""
            val bad = s230Change("vfs", "todo_items", "missing-parent", data)
            assertThrows(Exception::class.java) { owner.apply("peer", 1, listOf(bad)) }
            assertEquals(0L, fixture.primary.cursor("peer"))
            assertEquals(0L, db.query("SELECT COUNT(*) FROM todo_items WHERE id='missing-parent'") { it.long(0) }.single())
        }
    }
}

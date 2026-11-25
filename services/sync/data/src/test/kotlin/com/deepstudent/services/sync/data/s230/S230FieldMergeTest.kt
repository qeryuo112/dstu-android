package com.deepstudent.services.sync.data.s230

import com.deepstudent.platform.database.SharedDatabase
import com.deepstudent.services.sync.data.apply.IncomingDomain
import com.deepstudent.services.sync.data.apply.RegisteredIncomingRows
import com.deepstudent.services.sync.data.apply.RegisteredSqlRowApplier
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files

class S230FieldMergeTest {
    private val root = Files.createTempDirectory("s230-field-merge-").toFile()

    @After fun cleanup() {
        root.deleteRecursively()
    }

    @Test fun pendingTodoTagsMergeWhenRemoteWinsButOldPendingRemainsUnpublished() {
        S230MigratedFixture(root, s230HostMigrationSource()).use { f ->
            val db = f.db(SharedDatabase.VFS)
            db.execute("INSERT INTO todo_lists(id,title,created_at,updated_at) VALUES('list','List','2026-01-01','2026-01-01')")
            db.execute(
                "INSERT INTO todo_items(id,todo_list_id,title,tags_json,created_at,updated_at) " +
                    "VALUES('item','list','Local','[\"local\"]','2026-01-01','2026-01-01')",
            )
            val registration = RegisteredIncomingRows(
                SharedDatabase.VFS,
                f.registry,
                listOf(IncomingDomain(SharedDatabase.VFS, "todo", listOf("todo_items"))),
            )
            val applier = RegisteredSqlRowApplier(registration)
            val data = """{"id":"item","todo_list_id":"list","title":"Remote","tags_json":"[\"remote\"]",""" +
                """"created_at":"2026-01-01","updated_at":"2026-01-02"}"""
            val remote = s230Change("vfs", "todo_items", "item", data)
            db.transaction { applier.apply(this, listOf(remote)) }
            assertEquals("[\"local\",\"remote\"]", db.query("SELECT tags_json FROM todo_items WHERE id='item'") { it.text(0) }.single())
        }
    }
}

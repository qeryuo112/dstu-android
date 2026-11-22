package com.deepstudent.features.notes.data

import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.platform.database.DatabaseFactory
import com.deepstudent.platform.database.MigrationSource
import com.deepstudent.platform.database.SharedDatabase
import com.deepstudent.platform.database.SqlValue
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class NoteSyncRowsTest {
    private val root = Files.createTempDirectory("note-sync-rows").toFile()
    private val repositoryRoot = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .first { File(it, "config/schema/final-lock.json").isFile }
    private val source = MigrationSource { repositoryRoot.resolve(it).readBytes() }

    @After fun cleanup() { root.deleteRecursively() }

    @Test fun readsAllPortableColumnsWithLosslessIntegersAndBlobsAfterReopen() {
        val file = root.resolve("vfs.db")
        DatabaseFactory.openShared(file, SharedDatabase.VFS, source).use { db ->
            db.execute("INSERT INTO resources (id,hash,type,storage_mode,data,created_at,updated_at) VALUES (?,?,?,?,?,?,?)",
                listOf(text("res_sync"), text("hash"), text("note"), text("inline"), text("content"),
                    SqlValue.Integer(9007199254740993L), SqlValue.Integer(9007199254740993L)))
            val row = NoteSyncRows.read(db, "resources", "id", "res_sync")!!
            assertEquals(JsonValue.integer(9007199254740993L), row["created_at"])
            assertEquals(JsonValue.text("content"), row["data"])
            assertFalse(row.containsKey("index_state"))
            assertNull(NoteSyncRows.read(db, "resources", "id", "missing"))
        }
        DatabaseFactory.openShared(file, SharedDatabase.VFS, source).use { db ->
            assertEquals(JsonValue.integer(9007199254740993L), NoteSyncRows.read(db, "resources", "id", "res_sync")!!["updated_at"])
        }
    }
}

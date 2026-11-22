package com.deepstudent.services.sync.data.changelog

import com.deepstudent.platform.database.DatabaseRegistry
import com.deepstudent.platform.database.MigrationSource
import com.deepstudent.platform.database.SharedDatabase
import com.deepstudent.platform.database.SqlDatabase
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RestoreSyncBaselineTest {
    @get:Rule val temp = TemporaryFolder()
    private val repository = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
        .first { it.resolve("config/schema/final-lock.json").isFile }
    private val source = MigrationSource { repository.resolve(it).readBytes() }

    @Test fun normalizesBothVersionsAndClearsConflictsAliasesAndTriggeredChanges() = open().use { db ->
        seed(db)
        val reset = resetSyncBaselineAfterRestore(db)
        assertTrue(reset.clearedChanges >= 2)
        assertEquals(2L, reset.normalizedRows)
        assertEquals(listOf("keep:7/7", "same:3/3", "unset:null/5", "extra:9/9"), content(db))
        assertTrue(ChangeLogReader(db, "vfs", { listOf("id") }).readAll().isEmpty())
        listOf("__sync_conflicts", "__sync_id_aliases").forEach { table ->
            assertEquals(0L, db.query("SELECT count(*) FROM $table") { it.long(0) }.single())
        }
        assertEquals("sentinel", db.query("SELECT label FROM plain_restore_probe") { it.text(0) }.single())
        val again = resetSyncBaselineAfterRestore(db)
        assertEquals(0L, again.clearedChanges)
        assertEquals(0L, again.normalizedRows)
    }

    @Test fun failingResetRollsBackNormalizationAndDoesNotFireUntrustedCleanupTriggers() = open().use { db ->
        seed(db)
        val old = content(db)
        db.execute("CREATE TRIGGER erase_business AFTER DELETE ON __sync_id_aliases BEGIN DELETE FROM restore_probe; END")
        assertThrows(IllegalStateException::class.java) { resetSyncBaselineAfterRestore(db) }
        assertEquals(old, content(db))
        listOf("__change_log", "__sync_conflicts", "__sync_id_aliases").forEach { table ->
            assertEquals(1L, db.query("SELECT count(*) FROM $table") { it.long(0) }.single())
        }
    }

    private fun open() = DatabaseRegistry.openShared(temp.root.resolve("vfs.db"), SharedDatabase.VFS, source)

    private fun content(db: SqlDatabase) = db.query("""
        SELECT title || ':' || coalesce(local_version,'null') || '/' || coalesce(sync_version,'null')
        FROM restore_probe ORDER BY id
    """.trimIndent()) { it.text(0) }

    private fun seed(db: SqlDatabase) = db.transaction {
        execute("CREATE TABLE restore_probe(id TEXT PRIMARY KEY, title TEXT, local_version INTEGER, sync_version INTEGER)")
        execute("INSERT INTO restore_probe VALUES('a','keep',7,NULL),('b','same',3,3),('c','unset',NULL,5),('d','keep',7,1)")
        execute("DELETE FROM restore_probe WHERE id='a'")
        execute("UPDATE restore_probe SET id='a' WHERE id='d'")
        execute("INSERT INTO restore_probe VALUES('d','extra',9,NULL)")
        execute("CREATE TABLE plain_restore_probe(label TEXT,local_version INTEGER)")
        execute("INSERT INTO plain_restore_probe VALUES('sentinel',8)")
        execute("CREATE TABLE IF NOT EXISTS __sync_conflicts(id TEXT)")
        execute("INSERT INTO __sync_conflicts VALUES('old')")
        execute("CREATE TABLE IF NOT EXISTS __sync_id_aliases(id TEXT)")
        execute("INSERT INTO __sync_id_aliases VALUES('old')")
        execute("DELETE FROM __change_log")
        execute("INSERT INTO __change_log(table_name,record_id,operation) VALUES('restore_probe','a','UPDATE')")
        execute("""CREATE TRIGGER capture_restore_probe AFTER UPDATE ON restore_probe
            BEGIN INSERT INTO __change_log(table_name,record_id,operation) VALUES('restore_probe',NEW.id,'UPDATE'); END""")
    }
}

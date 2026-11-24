package com.deepstudent.services.resources.data

import com.deepstudent.core.common.error.DstuException
import com.deepstudent.core.common.id.ResourceId
import com.deepstudent.core.common.time.SystemWallClock
import com.deepstudent.platform.database.DatabaseFactory
import com.deepstudent.platform.database.MigrationSource
import com.deepstudent.platform.database.SharedDatabase
import com.deepstudent.platform.database.SqlDatabase
import com.deepstudent.services.resources.api.AttachmentMetadata
import com.deepstudent.services.resources.api.InlineNoteResource
import com.deepstudent.services.resources.api.StreamAttachmentRequest
import com.deepstudent.services.resources.api.WorkspaceFileArea
import com.deepstudent.services.resources.api.WorkspaceFileIds
import com.deepstudent.services.resources.api.WorkspaceFileWrite
import com.deepstudent.services.resources.api.workspaceVersion
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File

class WorkspaceResourceStoreTest {
    @get:Rule val dir = TemporaryFolder()
    private val id = WorkspaceFileIds.create()
    private val title = "中文 synthetic.txt"

    @Test fun existingIdentityHasOneVfsChangePerMutationAndSurvivesReopen() = runBlocking {
        val bytes = ByteArray(1024 * 1024 + 41) { (it % 223).toByte() }
        database().use { db ->
            val store = repository(db)
            val original = write(store, "draft".toByteArray())
            assertEquals(listOf("INSERT"), ops(db))
            val updated = write(store, bytes, original)
            assertEquals(id, updated.id)
            assertEquals(listOf("INSERT", "UPDATE"), ops(db))
            assertEquals(updated, write(store, bytes, updated))
            assertEquals(listOf("INSERT", "UPDATE"), ops(db))
            assertThrows(DstuException::class.java) { runBlocking { write(store, "stale".toByteArray(), original) } }
            val renamed = store.renameWorkspace(id, "renamed.txt")
            assertEquals(id, renamed.id)
            assertEquals(listOf("INSERT", "UPDATE", "UPDATE"), ops(db))
            assertEquals(
                1L,
                db.query("SELECT count(*) FROM resources WHERE id=?", listOf(text(original.resourceId.value))) {
                    it.long(0)
                }.single(),
            )
        }
        database().use { db ->
            val store = repository(db)
            val output = ByteArrayOutputStream()
            assertEquals("renamed.txt", store.read(id, output)?.name)
            assertArrayEquals(bytes, output.toByteArray())
            store.deleteWorkspace(id)
            assertNull(store.stat(id))
            assertNull(store.read(id, output))
            assertEquals(listOf("INSERT", "UPDATE", "UPDATE", "UPDATE"), ops(db))
            assertTrue(db.query("PRAGMA foreign_key_check") { it.text(0) }.isEmpty())
        }
    }

    @Test fun attachmentCollisionFailsWithoutHijackingAnExistingResourceOrRecordingAChange() = runBlocking {
        database().use { db ->
            val store = repository(db)
            val bytes = "already owned".toByteArray()
            val original = store.createStream(StreamAttachmentRequest("private-test.txt", "text/plain"), bytes.inputStream()) {}
            assertEquals(listOf(original.id), store.page(WorkspaceFileArea.ATTACHMENTS, null, 100).map { it.id })
            assertThrows(DstuException::class.java) { runBlocking { write(store, bytes) } }
            assertTrue(store.page(WorkspaceFileArea.WORKSPACE, null, 100).isEmpty())
            assertThrows(IllegalArgumentException::class.java) { runBlocking { store.deleteWorkspace(original.id) } }
            assertEquals(original, store.stat(original.id))
            db.transaction { SqlInlineResourceWriter().put(this, InlineNoteResource(ResourceId.parse("res_note"), "note", "secret", 1)) }
            assertNull(store.stat("res_note"))
            assertNull(store.stat("../../databases/vfs.db"))
            assertTrue(ops(db).isEmpty())
        }
    }

    private fun ops(db: SqlDatabase): List<String> = db.query(
        "SELECT operation FROM __change_log WHERE table_name='files' AND record_id=? ORDER BY id",
        listOf(text(id)),
    ) { it.text(0) }

    private suspend fun write(store: SqlAttachmentRepository, bytes: ByteArray, previous: AttachmentMetadata? = null) = store.write(
        WorkspaceFileWrite(
            id,
            StreamAttachmentRequest(previous?.name ?: title, "text/plain", bytes.size.toLong()),
            previous?.workspaceVersion(),
        ),
        bytes.inputStream(),
    )

    private fun repository(db: SqlDatabase) = SqlAttachmentRepository(db, dir.root.resolve("blobs"), SystemWallClock)
    private fun database(): SqlDatabase {
        val repo = generateSequence(File(checkNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .first { it.resolve("config/schema/final-lock.json").isFile }
        return DatabaseFactory.openShared(dir.root.resolve("vfs.db"), SharedDatabase.VFS, MigrationSource { repo.resolve(it).readBytes() })
    }
}

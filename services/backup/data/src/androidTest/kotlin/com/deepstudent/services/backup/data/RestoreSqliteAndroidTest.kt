package com.deepstudent.services.backup.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.deepstudent.platform.storage.FileDataSpace
import com.deepstudent.services.backup.api.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Synthetic, private-cache-only SQLite/WAL and blob fixture; never opens the app's active data. */
@RunWith(AndroidJUnit4::class)
class RestoreSqliteAndroidTest {
    private lateinit var sandbox: File

    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        sandbox = File(context.cacheDir, "s211-contract-${UUID.randomUUID()}")
        check(sandbox.mkdir())
    }

    @After fun cleanup() { sandbox.deleteRecursively() }

    @Test fun realSqliteQuickCheckAndWalSnapshotSurviveMappedOverlay() {
        val inputDb = File(sandbox, "input.db")
        val blob = "synthetic-blob-for-s211".toByteArray()
        val hash = digest(blob)
        createVfs(inputDb, hash)
        val vfs = inputDb.readBytes()
        val manifest = "synthetic-preflight-only".toByteArray()
        val entries = mapOf("manifest.json" to manifest, "vfs.db" to vfs, "assets/vfs_blobs/aa/$hash" to blob)
        val plan = fixturePlan(vfs, blob, hash, manifest)
        val root = File(sandbox, "private-files").apply { check(mkdir()) }
        val space = FileDataSpace(root)
        space.acquireActive().use { lease ->
            val databases = File(lease.root, "databases").apply { check(mkdir()) }
            createWalFixture(File(sandbox, "chat-source.db"), File(databases, "chat_v2.db"))
            File(databases, "runtime.db").writeText("device-local-placeholder")
            File(lease.root, "vfs_blobs/old").apply { check(mkdirs()) }
        }
        val verifier = object : RestoreCandidateVerifier {
            override fun verifyDatabases(candidate: File) {
                checkSqlite(File(candidate, "databases/vfs.db"), "synthetic-vfs")
                checkSqlite(File(candidate, "databases/chat_v2.db"), "wal-preserved")
                assertEquals("device-local-placeholder", File(candidate, "databases/runtime.db").readText())
            }
            override fun verifyResources(candidate: File) {
                val stored = File(candidate, "vfs_blobs/aa/$hash")
                assertEquals(hash, digest(stored.readBytes()))
                SQLiteDatabase.openDatabase(File(candidate, "databases/vfs.db").path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                    db.rawQuery("SELECT hash FROM blob_ref", null).use { cursor ->
                        assertTrue(cursor.moveToFirst()); assertEquals(hash, cursor.getString(0))
                    }
                }
            }
        }
        val executor = RestoreExecutor(verifier, ArchivePlanReader { _, _, _ -> plan })
        val result = executor.execute(space, "sqlite-android", plan) { BytesArchive(entries) }
        assertEquals(RestoreExecutionResult.Committed("sqlite-android", CommitDisposition.NEWLY_COMMITTED), result)
        space.acquireActive().use { lease ->
            checkSqlite(File(lease.root, "databases/chat_v2.db"), "wal-preserved")
            assertTrue(File(lease.root, "vfs_blobs/aa/$hash").isFile)
        }
        assertTrue(File(root, "active/databases/chat_v2.db-wal").isFile)
    }

    private fun createVfs(file: File, hash: String) {
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("CREATE TABLE payload(value TEXT NOT NULL)")
            db.execSQL("INSERT INTO payload VALUES('synthetic-vfs')")
            db.execSQL("CREATE TABLE blob_ref(hash TEXT NOT NULL)")
            db.execSQL("INSERT INTO blob_ref VALUES(?)", arrayOf(hash))
        }
    }

    private fun createWalFixture(source: File, destination: File) {
        SQLiteDatabase.openOrCreateDatabase(source, null).use { db ->
            check(db.enableWriteAheadLogging())
            db.rawQuery("PRAGMA wal_autocheckpoint=0", null).use { it.moveToFirst() }
            db.execSQL("CREATE TABLE payload(value TEXT NOT NULL)")
            db.execSQL("INSERT INTO payload VALUES('wal-preserved')")
            val wal = File(source.path + "-wal")
            check(wal.isFile && wal.length() > 0)
            source.copyTo(destination)
            wal.copyTo(File(destination.path + "-wal"))
        }
    }

    private fun checkSqlite(file: File, expected: String) {
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("PRAGMA quick_check", null).use { c -> assertTrue(c.moveToFirst()); assertEquals("ok", c.getString(0)) }
            db.rawQuery("SELECT value FROM payload", null).use { c -> assertTrue(c.moveToFirst()); assertEquals(expected, c.getString(0)) }
        }
    }

    private fun fixturePlan(db: ByteArray, blob: ByteArray, hash: String, manifest: ByteArray) = RestorePlan(
        snapshotKind = SnapshotKind.FULL, source = RestorePlanSource("synthetic", digest(manifest)),
        domains = listOf(
            DomainPreview("database:vfs", CoverageStatus.COMPLETE, DomainAction.STAGE_IN_SLOT, 1, db.size.toLong()),
            DomainPreview("asset-root:vfs_blobs", CoverageStatus.COMPLETE, DomainAction.STAGE_IN_SLOT, 1, blob.size.toLong()),
        ),
        files = listOf(
            PlannedFile("vfs.db", "databases/vfs.db", db.size.toLong(), digest(db)),
            PlannedFile("assets/vfs_blobs/aa/$hash", "vfs_blobs/aa/$hash", blob.size.toLong(), hash),
        ), requiredBytes = 1024, problems = emptyList(), canStage = true,
    )

    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    private class BytesArchive(private val files: Map<String, ByteArray>) : RestoreArchive {
        override fun inspect() = ArchiveCatalog(files.map { ArchiveEntry(it.key, ArchiveEntryKind.FILE, it.value.size.toLong()) })
        override fun open(path: String) = ByteArrayInputStream(files.getValue(path))
        override fun close() = Unit
    }
}

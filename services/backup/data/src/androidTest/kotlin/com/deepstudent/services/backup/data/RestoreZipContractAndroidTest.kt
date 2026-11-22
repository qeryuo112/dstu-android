package com.deepstudent.services.backup.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.deepstudent.platform.backupzip.CheckedZipArchive
import com.deepstudent.platform.storage.FileDataSpace
import com.deepstudent.services.backup.backend.ArchivePreflight
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** True ZIP adapter + production preflight + real SQLite; migration-history verifier is a later binding. */
@RunWith(AndroidJUnit4::class)
class RestoreZipContractAndroidTest {
    private lateinit var sandbox: File
    private val ids = listOf("vfs", "chat_v2", "mistakes", "llm_usage")

    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        sandbox = File(context.cacheDir, "s211-real-zip-${UUID.randomUUID()}")
        check(sandbox.mkdir())
    }
    @After fun cleanup() { sandbox.deleteRecursively() }

    @Test fun checkedZipAndActualSqliteRestoreThroughPrivateSlot() {
        val payload = ids.associateWith { createDatabase(it) }
        val manifest = actualManifest(payload)
        val archive = File(sandbox, "synthetic.zip")
        ZipOutputStream(archive.outputStream()).use { zip ->
            writeEntry(zip, "manifest.json", manifest)
            for ((id, bytes) in payload) writeEntry(zip, "$id.db", bytes)
        }
        val plan = CheckedZipArchive(archive).use { ArchivePreflight.inspect(it, Long.MAX_VALUE).plan }
        assertTrue(plan.problems.toString(), plan.canStage)
        val files = File(sandbox, "files").apply { check(mkdir()) }
        val space = FileDataSpace(files)
        space.acquireActive().use { lease ->
            File(lease.root, "databases").apply { check(mkdir()) }
            File(lease.root, "databases/runtime.db").writeText("device-local")
        }
        val verifier = object : RestoreCandidateVerifier {
            override fun verifyDatabases(candidate: File) { ids.forEach { checkDatabase(candidate, it) } }
            override fun verifyResources(candidate: File) = Unit
        }
        val result = RestoreExecutor(verifier).execute(space, "real-zip-android", plan) { CheckedZipArchive(archive) }
        assertEquals(RestoreExecutionResult.Committed("real-zip-android", CommitDisposition.NEWLY_COMMITTED), result)
        space.acquireActive().use { lease ->
            ids.forEach { checkDatabase(lease.root, it) }
            assertEquals("device-local", File(lease.root, "databases/runtime.db").readText())
        }
    }

    private fun createDatabase(id: String): ByteArray {
        val file = File(sandbox, "$id.source.db")
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("CREATE TABLE synthetic(value TEXT NOT NULL)")
            db.execSQL("INSERT INTO synthetic VALUES(?)", arrayOf(id))
        }
        return file.readBytes()
    }

    private fun actualManifest(payload: Map<String, ByteArray>): ByteArray {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val text = assets.open("desktop-v3-full.json").bufferedReader().use { it.readText() }
        val root = JSONObject(text)
        val files = root.getJSONArray("files")
        val domains = root.getJSONObject("coverage").getJSONObject("domains")
        for (index in 0 until files.length()) {
            val file = files.getJSONObject(index)
            val id = file.getString("database_id")
            val bytes = payload.getValue(id)
            file.put("size", bytes.size)
            file.put("sha256", digest(bytes))
            domains.getJSONObject("database:$id").put("total_size", bytes.size)
        }
        return root.toString().toByteArray(Charsets.UTF_8)
    }

    private fun checkDatabase(root: File, id: String) {
        SQLiteDatabase.openDatabase(File(root, "databases/$id.db").path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("PRAGMA quick_check", null).use { c -> assertTrue(c.moveToFirst()); assertEquals("ok", c.getString(0)) }
            db.rawQuery("SELECT value FROM synthetic", null).use { c -> assertTrue(c.moveToFirst()); assertEquals(id, c.getString(0)) }
        }
    }

    private fun writeEntry(zip: ZipOutputStream, path: String, bytes: ByteArray) {
        zip.putNextEntry(ZipEntry(path)); zip.write(bytes); zip.closeEntry()
    }
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}

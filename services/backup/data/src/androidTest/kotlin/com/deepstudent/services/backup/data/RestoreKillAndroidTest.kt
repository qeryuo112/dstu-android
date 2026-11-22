package com.deepstudent.services.backup.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.deepstudent.platform.backupzip.CheckedZipArchive
import com.deepstudent.platform.storage.DataSpacePhase
import com.deepstudent.platform.storage.DataSpacePhaseHook
import com.deepstudent.platform.storage.FileDataSpace
import com.deepstudent.services.backup.backend.ArchivePreflight
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Host controls SIGKILL; all persistent state belongs to this test package's cache. */
@RunWith(AndroidJUnit4::class)
class RestoreKillAndroidTest {
    private val args get() = InstrumentationRegistry.getArguments()
    private val runId get() = requireNotNull(args.getString("s211Run")).also { require(it.matches(Regex("[A-Za-z0-9_-]{1,96}"))) }
    private val phase get() = requireNotNull(args.getString("s211Phase")).also { require(it in setOf("ENTRY_WRITTEN", "STAGING_DURABLE", "POINTER_REPLACED")) }
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val sandbox get() = File(context.cacheDir, "s211-$runId")

    @Test fun checkpoint() {
        check(sandbox.mkdir())
        val root = File(sandbox, "files").apply { check(mkdir()) }
        val archive = createArchive()
        val first = FileDataSpace(root)
        first.acquireActive().use { lease ->
            File(lease.root, "databases").apply { check(mkdir()) }
            File(lease.root, "databases/vfs.db").writeText("old-vfs")
            File(lease.root, "databases/runtime.db").writeText("device-local")
        }
        val space = FileDataSpace(root, phaseHook = DataSpacePhaseHook {
            if (it == DataSpacePhase.STAGING_DURABLE) pauseAt("STAGING_DURABLE")
            if (it == DataSpacePhase.POINTER_REPLACED) pauseAt("POINTER_REPLACED")
        })
        val executor = RestoreExecutor(verifier, hook = RestoreCheckpointHook { if (it == RestoreCheckpoint.ENTRY_WRITTEN) pauseAt("ENTRY_WRITTEN") })
        val plan = CheckedZipArchive(archive).use { ArchivePreflight.inspect(it, Long.MAX_VALUE).plan }
        assertTrue(plan.problems.toString(), plan.canStage)
        executor.execute(space, "s211-$runId", plan) { CheckedZipArchive(archive) }
        fail("Expected external SIGKILL at $phase")
    }

    @Test fun verifyAndResume() {
        val receipt = File(sandbox, "receipt").readLines()
        assertEquals(3, receipt.size)
        assertEquals("version=1", receipt[0])
        assertEquals("phase=$phase", receipt[1])
        val oldPid = receipt[2].removePrefix("pid=").toInt()
        assertNotEquals(oldPid, Process.myPid())
        val root = File(sandbox, "files")
        val space = FileDataSpace(root)
        val afterSwitch = phase == "POINTER_REPLACED"
        space.acquireActive().use { lease ->
            if (afterSwitch) checkDatabase(lease.root, "vfs")
            else assertEquals("old-vfs", File(lease.root, "databases/vfs.db").readText())
        }
        val archive = File(sandbox, "source.zip")
        val plan = CheckedZipArchive(archive).use { ArchivePreflight.inspect(it, Long.MAX_VALUE).plan }
        assertTrue(plan.problems.toString(), plan.canStage)
        var opens = 0
        val first = RestoreExecutor(verifier).execute(space, "s211-$runId", plan) { opens++; CheckedZipArchive(archive) }
        val expectedDisposition = if (afterSwitch) CommitDisposition.ALREADY_ACTIVE else CommitDisposition.NEWLY_COMMITTED
        assertEquals(RestoreExecutionResult.Committed("s211-$runId", expectedDisposition), first)
        assertEquals(if (afterSwitch) 0 else 1, opens)
        val again = RestoreExecutor(verifier).execute(space, "s211-$runId", plan) { error("committed operation reopened archive") }
        assertEquals(RestoreExecutionResult.Committed("s211-$runId", CommitDisposition.ALREADY_ACTIVE), again)
        assertEquals("old-vfs", File(root, "active/databases/vfs.db").readText())
        space.acquireActive().use { lease ->
            for (id in ids) checkDatabase(lease.root, id)
            assertEquals("device-local", File(lease.root, "databases/runtime.db").readText())
        }
        val before = if (afterSwitch) "new" else "old"
        val status = Bundle().apply { putString("s211Verify", "S211_VERIFY phase=$phase oldPid=$oldPid newPid=${Process.myPid()} activeBefore=$before active=new old=preserved disposition=$expectedDisposition sameOp=once") }
        InstrumentationRegistry.getInstrumentation().sendStatus(2, status)
        check(sandbox.deleteRecursively())
    }

    private fun pauseAt(point: String) {
        if (phase != point) return
        val receipt = "version=1\nphase=$point\npid=${Process.myPid()}\n".toByteArray()
        FileOutputStream(File(sandbox, "receipt")).use { it.write(receipt); it.fd.sync() }
        while (true) SystemClock.sleep(1_000)
    }

    private val ids = listOf("vfs", "chat_v2", "mistakes", "llm_usage")

    private fun createArchive(): File {
        val zipFile = File(sandbox, "source.zip")
        val payload = ids.associateWith { createDatabase(it) }
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val manifest = JSONObject(assets.open("desktop-v3-full.json").bufferedReader().use { it.readText() })
        val files = manifest.getJSONArray("files")
        val domains = manifest.getJSONObject("coverage").getJSONObject("domains")
        for (index in 0 until files.length()) {
            val entry = files.getJSONObject(index)
            val id = entry.getString("database_id")
            val bytes = payload.getValue(id)
            entry.put("size", bytes.size)
            entry.put("sha256", digest(bytes))
            domains.getJSONObject("database:$id").put("total_size", bytes.size)
        }
        ZipOutputStream(zipFile.outputStream()).use { zip ->
            val entries = payload.mapKeys { "${it.key}.db" } + ("manifest.json" to manifest.toString().toByteArray())
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
            }
        }
        return zipFile
    }

    private fun createDatabase(id: String): ByteArray {
        val file = File(sandbox, "$id.input.db")
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("CREATE TABLE synthetic(value TEXT NOT NULL)")
            db.execSQL("INSERT INTO synthetic VALUES(?)", arrayOf(id))
        }
        return file.readBytes()
    }

    private fun checkDatabase(root: File, id: String) {
        SQLiteDatabase.openDatabase(File(root, "databases/$id.db").path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("PRAGMA quick_check", null).use { cursor ->
                assertTrue(cursor.moveToFirst()); assertEquals("ok", cursor.getString(0))
            }
            db.rawQuery("SELECT value FROM synthetic", null).use { cursor ->
                assertTrue(cursor.moveToFirst()); assertEquals(id, cursor.getString(0))
            }
        }
    }

    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    private val verifier = object : RestoreCandidateVerifier {
        override fun verifyDatabases(candidate: File) {
            for (id in ids) checkDatabase(candidate, id)
            assertEquals("device-local", File(candidate, "databases/runtime.db").readText())
        }
        override fun verifyResources(candidate: File) = Unit
    }
}

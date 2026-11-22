package com.deepstudent.platform.storage

import android.os.Bundle
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Host starts each method separately. checkpoint intentionally kills its own test process. */
@RunWith(AndroidJUnit4::class)
class DataSpaceDeathAndroidTest {
    private val args get() = InstrumentationRegistry.getArguments()

    @Test fun checkpoint() {
        val config = config()
        val fixture = fixture(config.run)
        check(fixture.mkdir()) { "host must use a fresh run identifier" }
        val root = File(fixture, "files").apply { check(mkdir()) }
        val legacy = File(root, "active").apply { check(mkdir()) }
        writeGeneration(legacy, "old")
        DurableFiles.syncTree(legacy)
        DurableFiles.syncDirectory(root)
        val hook = DataSpacePhaseHook { phase ->
            if (phase == config.phase) {
                writeReceipt(fixture, phase)
                Process.killProcess(Process.myPid())
                error("process survived SIGKILL")
            }
        }
        val store = FileDataSpace(root, phaseHook = hook)
        store.acquireActive().close()
        store.withMaintenance {
            val stage = prepare("s209-${config.run}")
            writeGeneration(stage.root, "new")
            activate(stage) { assertGeneration(it, "new") }
        }
        error("checkpoint was not reached")
    }

    @Test fun verify() {
        val config = config()
        val fixture = fixture(config.run)
        val receipt = parseReceipt(File(fixture, "receipt").readText())
        assertEquals(config.phase.name, receipt.first)
        val pid = Process.myPid()
        assertNotEquals(receipt.second, pid)
        val root = File(fixture, "files")
        val expected = if (config.phase.ordinal >= DataSpacePhase.POINTER_REPLACED.ordinal) "new" else "old"
        FileDataSpace(root).acquireActive().use { assertGeneration(it.root, expected) }
        assertGeneration(File(root, "active"), "old")
        val status = "S209_VERIFY phase=${config.phase} oldPid=${receipt.second} newPid=$pid active=$expected old=preserved"
        println(status)
        InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply { putString("s209Verify", status) })
    }

    private fun config(): Config {
        val run = args.getString("s209Run")
        assumeTrue("host must orchestrate process death", run != null)
        require(run != null && run.matches(Regex("s209-death-[0-9]+-[0-9]+")))
        val phase = DataSpacePhase.valueOf(checkNotNull(args.getString("s209Phase")))
        require(phase in phases)
        return Config(run, phase)
    }

    private fun fixture(run: String): File = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, run)

    private fun writeReceipt(fixture: File, phase: DataSpacePhase) {
        val text = "version=1\nphase=${phase.name}\npid=${Process.myPid()}\n"
        val receipt = File(fixture, "receipt")
        check(!receipt.exists())
        FileOutputStream(receipt).use { it.write(text.toByteArray(Charsets.US_ASCII)); it.fd.sync() }
        DurableFiles.syncDirectory(fixture)
        println("S209_CHECKPOINT phase=$phase pid=${Process.myPid()}")
    }

    private fun parseReceipt(text: String): Pair<String, Int> {
        val lines = text.split('\n')
        assertEquals(4, lines.size)
        assertEquals("version=1", lines[0])
        assertTrue(lines[1].startsWith("phase="))
        assertTrue(lines[2].startsWith("pid="))
        return lines[1].removePrefix("phase=") to lines[2].removePrefix("pid=").toInt()
    }

    private fun writeGeneration(dir: File, generation: String) {
        File(dir, "generation").writeText("$generation-generation\n")
        File(dir, "databases").mkdir()
        File(dir, "databases/data.db").writeText("$generation-database\n")
        File(dir, "assets").mkdir()
        File(dir, "assets/blob").writeText("$generation-asset\n")
    }

    private fun assertGeneration(dir: File, generation: String) {
        assertEquals("$generation-generation\n", File(dir, "generation").readText())
        assertEquals("$generation-database\n", File(dir, "databases/data.db").readText())
        assertEquals("$generation-asset\n", File(dir, "assets/blob").readText())
    }

    private data class Config(val run: String, val phase: DataSpacePhase)

    private companion object {
        val phases = setOf(
            DataSpacePhase.STAGING_PREPARED, DataSpacePhase.STAGING_VALIDATED, DataSpacePhase.STAGING_DURABLE,
            DataSpacePhase.POINTER_TEMP_DURABLE, DataSpacePhase.POINTER_REPLACED,
            DataSpacePhase.POINTER_DURABLE, DataSpacePhase.COMMIT_RECORDED,
        )
    }
}

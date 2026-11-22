package com.deepstudent.features.terminal.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.deepstudent.features.terminal.api.TerminalPhase
import com.deepstudent.features.terminal.api.TerminalSessionId
import com.deepstudent.features.terminal.api.TerminalSessionSnapshot
import com.deepstudent.features.terminal.api.TerminalSize
import com.deepstudent.features.terminal.api.TerminalSessions
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry

@RunWith(AndroidJUnit4::class)
class TerminalSessionsDeviceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val terminalDirectory = File(context.filesDir, "terminal").apply { mkdirs() }
    private val terminalService = OperitTerminalPlatform(terminalDirectory).createService()
    private val service: TerminalSessions = terminalService
    private val host = PtyForegroundHost()

    @Before fun foreground() { host.start() }

    @After
    fun cleanup() = runBlocking {
        try { terminalService.shutdown() } finally { host.close() }
    }

    @Test(timeout = 60_000)
    fun shellResizeUnicodeInterruptAndExitCodesAreIsolated() = runBlocking {
        stage("open")
        val first = service.open(TerminalSize(80, 24))
        val second = service.open(TerminalSize(70, 20))
        stage("write")
        service.write(first, "stty -echo; printf 'FIRST 中😀\\n'; stty size\n")
        service.write(second, "stty -echo; printf 'SECOND\\n'; stty size\n")
        stage("await-first")
        awaitText(first, "FIRST 中😀")
        awaitText(first, "24 80")
        awaitText(second, "SECOND")
        awaitText(second, "20 70")
        stage("resize")
        assertFalse(text(first).contains("SECOND"))
        assertFalse(text(second).contains("FIRST"))

        service.resize(first, TerminalSize(91, 31))
        service.write(first, "stty size\n")
        awaitText(first, "31 91")
        service.write(first, "sleep 30\n")
        delay(300)
        service.interrupt(first)
        stage("interrupt")
        service.write(first, "printf 'AFTER_INTERRUPT\\n'\n")
        awaitText(first, "AFTER_INTERRUPT")

        service.write(first, "exit 37\n")
        service.write(second, "exit 9\n")
        awaitExit(first, 37)
        awaitExit(second, 9)
        stage("exits-verified")
    }

    @Test
    fun closeReclaimsDescriptorsForRepeatedSessions() = runBlocking {
        // Bring up renderer/JIT and the I/O dispatcher before measuring the PTY owner's steady state.
        repeat(2) { cycle(it) }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        delay(500)
        val before = descriptorCount()
        val ptyBefore = ptyDescriptorCount()
        repeat(4) {
            cycle(it)
            assertEquals("PTY descriptors were not reclaimed", ptyBefore, ptyDescriptorCount())
        }
        delay(300)
        val after = descriptorCount()
        stage("fd-before-$before-after-$after-pty-$ptyBefore-${ptyDescriptorCount()}")
        assertTrue("fd count grew from $before to $after", after <= before + 1)
    }

    private suspend fun cycle(index: Int) {
        val id = service.open(TerminalSize(40, 12))
        service.write(id, "printf 'READY_$index\\n'\n")
        awaitText(id, "READY_$index")
        service.close(id)
    }

    private suspend fun awaitText(id: TerminalSessionId, value: String) = withTimeout(15_000) {
        while (!text(id).contains(value)) delay(30)
    }

    private suspend fun awaitExit(id: TerminalSessionId, code: Int) = withTimeout(15_000) {
        while (state(id)?.let { it.phase != TerminalPhase.EXITED || it.exitCode != code } != false) {
            delay(30)
        }
    }

    private fun text(id: TerminalSessionId): String = state(id)?.screen?.lines.orEmpty().joinToString("\n") { row ->
        row.joinToString("") { it.text }.trimEnd()
    }

    private fun state(id: TerminalSessionId): TerminalSessionSnapshot? = service.observe(id)?.value

    private fun descriptorCount(): Int = File("/proc/self/fd").listFiles()?.size ?: -1
    private fun ptyDescriptorCount(): Int = File("/proc/self/fd").listFiles().orEmpty().count {
        runCatching { android.system.Os.readlink(it.path).startsWith("/dev/pts/") }.getOrDefault(false)
    }

    private fun stage(value: String) { InstrumentationRegistry.getInstrumentation().sendStatus(2,
        Bundle().apply { putString("stage", "terminal.$value") }) }
}
